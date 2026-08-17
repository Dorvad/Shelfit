package com.shelfit.sentinel.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.shelfit.sentinel.core.WallClock
import com.shelfit.sentinel.core.sensormode.ListeningMode
import com.shelfit.sentinel.core.sensormode.SensorModeError
import com.shelfit.sentinel.data.SensorModeStore
import kotlinx.coroutines.flow.first

/**
 * The one way anything in the app turns listening on or off.
 *
 * The desired mode is written here rather than in the service, so it survives the
 * service failing to start at all. That is the difference between "Android would not let
 * us start" — recoverable, and shown on the health screen — and "the user turned it
 * off".
 *
 * Every call must originate from something the user did while the app is visible.
 * Android grants microphone while-in-use access on that basis, and a background attempt
 * to start a microphone foreground service is refused. That refusal is handled, not
 * worked around.
 */
class SensorModeController(
    context: Context,
    private val store: SensorModeStore,
    private val clock: WallClock,
) {

    private val appContext = context.applicationContext

    /** @return true if the service was asked to start; false if Android refused. */
    suspend fun enable(): Boolean {
        store.setDesiredMode(ListeningMode.LISTENING)
        return send(SensorModeService.ACTION_START)
    }

    suspend fun pause(): Boolean {
        store.setDesiredMode(ListeningMode.PAUSED)
        return send(SensorModeService.ACTION_PAUSE)
    }

    suspend fun resume(): Boolean {
        store.setDesiredMode(ListeningMode.LISTENING)
        return send(SensorModeService.ACTION_RESUME)
    }

    suspend fun disable() {
        store.setDesiredMode(ListeningMode.OFF)
        appContext.stopService(Intent(appContext, SensorModeService::class.java))
    }

    /**
     * Releases the microphone for something that needs it exclusively — calibration.
     *
     * @return the mode that was in force, to hand back to [restore].
     */
    suspend fun releaseForExclusiveUse(): ListeningMode {
        val previous = store.record.first().desiredMode
        if (previous != ListeningMode.OFF) {
            appContext.stopService(Intent(appContext, SensorModeService::class.java))
        }
        return previous
    }

    /** Puts back whatever [releaseForExclusiveUse] took away. */
    suspend fun restore(previous: ListeningMode) {
        when (previous) {
            ListeningMode.LISTENING -> enable()
            ListeningMode.PAUSED -> pause()
            ListeningMode.OFF -> Unit
        }
    }

    private suspend fun send(action: String): Boolean = try {
        ContextCompat.startForegroundService(
            appContext,
            Intent(appContext, SensorModeService::class.java).setAction(action),
        )
        true
    } catch (error: IllegalStateException) {
        // ForegroundServiceStartNotAllowedException on API 31+, whose concrete type is
        // version-gated. Reached when this is called from the background.
        recordBlocked(error)
        false
    } catch (error: SecurityException) {
        // API 34+: the microphone type needs while-in-use access we do not currently have.
        recordBlocked(error)
        false
    }

    private suspend fun recordBlocked(error: Throwable) {
        store.recordError(
            SensorModeError(
                kind = SensorModeError.Kind.FOREGROUND_START_BLOCKED,
                atEpochMillis = clock.epochMillis(),
                detail = error.message,
            ),
        )
    }
}
