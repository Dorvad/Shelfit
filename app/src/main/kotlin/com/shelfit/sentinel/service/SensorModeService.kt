package com.shelfit.sentinel.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.shelfit.sentinel.AppContainer
import com.shelfit.sentinel.R
import com.shelfit.sentinel.SentinelApplication
import com.shelfit.sentinel.core.diagnostics.DiagnosticEvent
import com.shelfit.sentinel.core.sensormode.DetectionSupervisor
import com.shelfit.sentinel.core.sensormode.ListeningMode
import com.shelfit.sentinel.core.sensormode.SensorModeError
import com.shelfit.sentinel.core.sensormode.SupervisorReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Sensor Mode: microphone monitoring that survives the screen going off.
 *
 * A foreground service is the only legitimate way to keep the microphone open on a
 * modern Android device, and its notification is not an inconvenience to work around —
 * it is how the user pauses, resumes and sees that the phone is listening.
 *
 * **Lifecycle contract.**
 *  - [onStartCommand] promotes to the foreground *before* any suspending work. The
 *    system allows only a few seconds after `startForegroundService`, and a missed
 *    deadline is a crash.
 *  - If promotion is refused, the service does not linger in a half-started state: it
 *    posts a notification the user can act on and stops itself.
 *  - Pausing releases the microphone but keeps the service in the foreground, which is
 *    what makes Resume reachable from the notification instead of only from the app.
 *  - [onDestroy] cancels the scope and stops detection, so no coroutine and no
 *    `AudioRecord` outlives the service.
 *
 * **No wake lock.** The intended device is plugged in, and Doze does not engage while
 * charging. `AudioRecord` plus a foreground service keeps the audio path alive on its
 * own, so taking a partial wake lock would add a resource to leak and buy nothing.
 */
class SensorModeService : Service() {

    /**
     * Service-scoped, cancelled in [onDestroy]. Nothing started here may outlive the
     * service — that is the difference between a long-running service and a leak.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var container: AppContainer
    private lateinit var notifications: SensorModeNotifications

    private var supervisorJob: Job? = null
    private var listening = false
    private var statusDetail: String? = null

    override fun onCreate() {
        super.onCreate()
        container = (application as SentinelApplication).container
        notifications = SensorModeNotifications(this)
        notifications.ensureChannels()
        container.sensorModeRuntime.onServiceCreated()

        scope.launch {
            container.sensorModeStore.recordServiceStart(container.wallClock.epochMillis())
        }
        scope.launch { observeDetections() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null action means the system recreated the service after the process died.
        val action = intent?.action ?: ACTION_RESTORE
        val wantsMicrophone = action != ACTION_PAUSE

        if (!promoteToForeground(wantsMicrophone)) {
            // Android refused. Do not sit here holding a broken service.
            notifications.postResumeRequired()
            stopSelf()
            return START_NOT_STICKY
        }

        when (action) {
            ACTION_PAUSE -> pauseListening()
            ACTION_RESTORE -> restoreDesiredMode()
            else -> beginListening()
        }

        // Sticky so that a process killed by memory pressure is given another chance.
        // If Android will not allow the restart, promotion above fails and we degrade to
        // a notification rather than crashing.
        return START_STICKY
    }

    override fun onDestroy() {
        supervisorJob = null
        scope.cancel()
        // Belt and braces: cancelling the supervisor's scope stops its work, but the
        // engine is owned by the container and has to be told explicitly.
        container.stopDetection()
        container.sensorModeRuntime.onServiceDestroyed()
        super.onDestroy()
    }

    /**
     * Enters the foreground with the microphone type declared.
     *
     * The microphone service type only exists from Android 11; service types did not
     * become mandatory until Android 14. On Android 10 — which is precisely the vintage of
     * phone this feature is for — declaring a type the platform does not know is a
     * mismatch, so the untyped call is the correct one there.
     *
     * Returns false when Android refuses, which happens for reasons outside the app's
     * control: a background start on Android 12 and later, or missing while-in-use
     * microphone access on Android 14 and later. The concrete exception types are
     * version-gated, so the portable supertypes are what can be caught here.
     */
    private fun promoteToForeground(listening: Boolean): Boolean = try {
        val notification = notifications.status(listening, statusDetail)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceCompat.startForeground(
                this,
                SensorModeNotifications.NOTIFICATION_STATUS,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(SensorModeNotifications.NOTIFICATION_STATUS, notification)
        }
        true
    } catch (error: IllegalStateException) {
        recordStartBlocked(error)
        false
    } catch (error: SecurityException) {
        recordStartBlocked(error)
        false
    }

    private fun recordStartBlocked(error: Throwable) {
        val record = SensorModeError(
            kind = SensorModeError.Kind.FOREGROUND_START_BLOCKED,
            atEpochMillis = container.wallClock.epochMillis(),
            detail = error.message,
        )
        // A detached scope: this service is about to stop, so its own scope cannot be
        // trusted to outlive the write. The application scope owns the container's
        // stores and is the right home for it.
        container.recordSensorModeError(record)
        container.eventLog.record(
            DiagnosticEvent.Kind.SERVICE_START_BLOCKED,
            error.message?.take(MAX_DETAIL),
        )
    }

    private fun beginListening() {
        listening = true
        notifications.clearAttention()
        updateNotification()

        if (supervisorJob?.isActive == true) return

        scope.launch { container.sensorModeStore.setDesiredMode(ListeningMode.LISTENING) }
        supervisorJob = scope.launch {
            try {
                DetectionSupervisor(
                    states = container.triggerEngine.states,
                    start = { container.startDetection() },
                    stop = { container.stopDetection() },
                    report = ::onSupervisorReport,
                ).run()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                // The supervisor is the thing that recovers from failure, so if it dies
                // there is nothing left watching. Surface it rather than going quiet.
                onSupervisorCrashed(error)
            }
        }
    }

    /**
     * Last resort. Capture is released and the notification says so, because a listening
     * indicator that is lying is worse than one that admits it stopped.
     */
    private suspend fun onSupervisorCrashed(error: Throwable) {
        listening = false
        container.stopDetection()
        statusDetail = error.message?.take(MAX_DETAIL)
        val record = SensorModeError(
            kind = SensorModeError.Kind.AUDIO_FAILURE,
            atEpochMillis = container.wallClock.epochMillis(),
            detail = error.message,
        )
        container.sensorModeStore.recordError(record)
        notifications.postAttention(record)
        container.eventLog.record(
            DiagnosticEvent.Kind.DETECTOR_FAILED,
            error.message?.take(MAX_DETAIL),
        )
        updateNotification()
    }

    private fun pauseListening() {
        supervisorJob?.cancel()
        supervisorJob = null
        listening = false
        statusDetail = null
        // The supervisor deliberately does not stop capture when cancelled, because the
        // caller may be handing the microphone to something else. Here we do want it
        // released.
        container.stopDetection()
        scope.launch { container.sensorModeStore.setDesiredMode(ListeningMode.PAUSED) }
        updateNotification()
        container.eventLog.record(DiagnosticEvent.Kind.SENSOR_MODE_PAUSED)
    }

    /** After a process restart, pick up whatever the user last asked for. */
    private fun restoreDesiredMode() {
        scope.launch {
            when (container.sensorModeStore.record.first().desiredMode) {
                ListeningMode.LISTENING -> beginListening()
                ListeningMode.PAUSED -> pauseListening()
                ListeningMode.OFF -> stopSelf()
            }
        }
    }

    private suspend fun onSupervisorReport(report: SupervisorReport) {
        when (report) {
            SupervisorReport.Started -> {
                statusDetail = null
                container.sensorModeStore.clearError()
                container.eventLog.record(DiagnosticEvent.Kind.SENSOR_MODE_LISTENING)
            }

            is SupervisorReport.Recovering -> {
                statusDetail = getString(
                    R.string.notification_recovering,
                    report.delayMillis / MILLIS_PER_SECOND,
                )
                container.sensorModeStore.recordError(
                    SensorModeError(
                        kind = SensorModeError.Kind.MICROPHONE_UNAVAILABLE,
                        atEpochMillis = container.wallClock.epochMillis(),
                        detail = report.detail,
                    ),
                )
                container.eventLog.record(
                    DiagnosticEvent.Kind.CAPTURE_RECOVERING,
                    "attempt ${report.attempt}, retrying in " +
                        "${report.delayMillis / MILLIS_PER_SECOND}s",
                )
            }

            is SupervisorReport.Recovered -> {
                statusDetail = null
                container.sensorModeStore.clearError()
                container.eventLog.record(
                    DiagnosticEvent.Kind.CAPTURE_RECOVERED,
                    "after ${report.afterAttempts} attempts",
                )
            }

            SupervisorReport.PermissionLost -> {
                listening = false
                statusDetail = getString(R.string.notification_permission_lost)
                val error = SensorModeError(
                    kind = SensorModeError.Kind.MICROPHONE_PERMISSION_REVOKED,
                    atEpochMillis = container.wallClock.epochMillis(),
                )
                container.sensorModeStore.recordError(error)
                notifications.postAttention(error)
                container.eventLog.record(DiagnosticEvent.Kind.MICROPHONE_PERMISSION_LOST)
            }
        }
        updateNotification()
    }

    /** Keeps the notification's "last heard" line current. Detections are rare. */
    private suspend fun observeDetections() {
        container.triggerEngine.events.collect {
            container.sensorModeStore.recordTrigger(container.wallClock.epochMillis())
            if (listening) updateNotification()
        }
    }

    private fun updateNotification() {
        notifications.updateStatus(listening, statusDetail)
    }

    companion object {
        const val ACTION_START = "com.shelfit.sentinel.action.START"
        const val ACTION_PAUSE = "com.shelfit.sentinel.action.PAUSE"
        const val ACTION_RESUME = "com.shelfit.sentinel.action.RESUME"

        /** Used when the system recreates the service with no intent. */
        private const val ACTION_RESTORE = "com.shelfit.sentinel.action.RESTORE"

        private const val MILLIS_PER_SECOND = 1_000L
        private const val MAX_DETAIL = 120
    }
}
