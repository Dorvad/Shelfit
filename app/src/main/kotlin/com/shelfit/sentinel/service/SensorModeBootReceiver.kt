package com.shelfit.sentinel.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.shelfit.sentinel.SentinelApplication
import com.shelfit.sentinel.core.diagnostics.DiagnosticEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Reacts to the device restarting and to this app being upgraded.
 *
 * **It does not start the microphone service, and that is deliberate.** Android does not
 * allow a microphone foreground service to be launched from a background broadcast
 * receiver on current versions: from Android 14 the microphone service type requires
 * while-in-use microphone access, which a receiver does not have, and Android 15
 * disallows launching this service type from `BOOT_COMPLETED` outright. Attempting it
 * would throw, and working around it would mean evading a restriction that exists for
 * good reason — a phone should not be able to start listening after a reboot without
 * its owner knowing.
 *
 * So the honest behaviour: remember that Sensor Mode was on, and put a notification in
 * front of the user that resumes it in a single tap.
 *
 * `MY_PACKAGE_REPLACED` is handled the same way, because an upgrade kills the process
 * and takes the service with it.
 */
class SensorModeBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        // Broadcast receivers get a few seconds; goAsync buys enough to read one small
        // preferences file and post a notification.
        val pendingResult = goAsync()
        val application = context.applicationContext as? SentinelApplication
        if (application == null) {
            pendingResult.finish()
            return
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val container = application.container
                val store = container.sensorModeStore

                if (action == Intent.ACTION_BOOT_COMPLETED) {
                    store.recordBoot(container.wallClock.epochMillis())
                }

                if (store.record.first().desiredMode.isEnabled) {
                    val notifications = SensorModeNotifications(application)
                    notifications.ensureChannels()
                    notifications.postResumeRequired()
                    container.eventLog.record(
                        DiagnosticEvent.Kind.RESUME_REQUIRED,
                        if (action == Intent.ACTION_BOOT_COMPLETED) "after restart" else "after update",
                    )
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
