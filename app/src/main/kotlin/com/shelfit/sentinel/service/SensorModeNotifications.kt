package com.shelfit.sentinel.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.shelfit.sentinel.MainActivity
import com.shelfit.sentinel.R
import com.shelfit.sentinel.core.sensormode.SensorModeError

/**
 * The two notifications Sensor Mode needs, and the intents behind their buttons.
 *
 * Two channels rather than one, because they mean different things and a user should be
 * able to silence the first without losing the second:
 *
 *  - **Status** is the ongoing foreground-service notification. Minimum importance: it
 *    has to exist for the service to be legal, and it should never make a sound.
 *  - **Attention** is used when something needs a person — after a reboot, or when the
 *    microphone permission has gone. Default importance, because it is the only way the
 *    user finds out.
 */
class SensorModeNotifications(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    fun ensureChannels() {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STATUS,
                context.getString(R.string.channel_status_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.channel_status_description)
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ATTENTION,
                context.getString(R.string.channel_attention_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.channel_attention_description)
            },
        )
    }

    /**
     * The ongoing notification.
     *
     * @param listening false while paused, which swaps Pause for Resume.
     * @param detail one line under the title — the recovery state, or the last detection.
     */
    fun status(listening: Boolean, detail: String?): android.app.Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                context.getString(
                    if (listening) R.string.notification_listening else R.string.notification_paused,
                ),
            )
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        detail?.let { builder.setContentText(it) }

        if (listening) {
            builder.addAction(
                R.drawable.ic_notification,
                context.getString(R.string.action_pause),
                serviceIntent(SensorModeService.ACTION_PAUSE, REQUEST_PAUSE),
            )
        } else {
            builder.addAction(
                R.drawable.ic_notification,
                context.getString(R.string.action_resume),
                serviceIntent(SensorModeService.ACTION_RESUME, REQUEST_RESUME),
            )
        }

        builder.addAction(
            R.drawable.ic_notification,
            context.getString(R.string.action_open_app),
            openAppIntent(),
        )

        return builder.build()
    }

    /**
     * Posted when Sensor Mode is on but nothing is listening — after a reboot, after an
     * upgrade, or after the process was killed.
     *
     * Tapping it opens the app with a resume request rather than starting the service
     * directly. That is not indirection for its own sake: from Android 14 a
     * microphone-type foreground service may only be started while the app has
     * while-in-use access to the microphone, and a visible activity is what grants that.
     * A notification tap alone does not.
     */
    fun postResumeRequired() {
        if (!manager.areNotificationsEnabled()) return
        manager.notify(
            NOTIFICATION_ATTENTION,
            NotificationCompat.Builder(context, CHANNEL_ATTENTION)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.notification_resume_title))
                .setContentText(context.getString(R.string.notification_resume_text))
                .setContentIntent(resumeIntent())
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build(),
        )
    }

    /** Posted when something needs the user and retrying will not fix it. */
    fun postAttention(error: SensorModeError) {
        if (!manager.areNotificationsEnabled()) return
        val text = when (error.kind) {
            SensorModeError.Kind.MICROPHONE_PERMISSION_REVOKED ->
                context.getString(R.string.notification_error_microphone_permission)

            SensorModeError.Kind.FOREGROUND_START_BLOCKED ->
                context.getString(R.string.notification_error_start_blocked)

            SensorModeError.Kind.MICROPHONE_UNAVAILABLE ->
                context.getString(R.string.notification_error_microphone_busy)

            SensorModeError.Kind.AUDIO_FAILURE ->
                context.getString(R.string.notification_error_audio)
        }
        manager.notify(
            NOTIFICATION_ATTENTION,
            NotificationCompat.Builder(context, CHANNEL_ATTENTION)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.notification_error_title))
                .setContentText(text)
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .build(),
        )
    }

    /**
     * Refreshes the ongoing notification in place.
     *
     * Silently does nothing when notifications are disabled: the service is still legal
     * and still listening, but Pause and Resume are unreachable, which is why the health
     * screen treats a denied notification permission as something to fix.
     */
    fun updateStatus(listening: Boolean, detail: String?) {
        if (!manager.areNotificationsEnabled()) return
        manager.notify(NOTIFICATION_STATUS, status(listening, detail))
    }

    fun clearAttention() = manager.cancel(NOTIFICATION_ATTENTION)

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_OPEN_APP,
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun resumeIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_RESUME_VIA_APP,
        Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_RESUME_SENSOR_MODE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            context,
            requestCode,
            Intent(context, SensorModeService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        const val CHANNEL_STATUS = "sensor_mode_status"
        const val CHANNEL_ATTENTION = "sensor_mode_attention"

        const val NOTIFICATION_STATUS = 1
        const val NOTIFICATION_ATTENTION = 2

        private const val REQUEST_OPEN_APP = 10
        private const val REQUEST_PAUSE = 11
        private const val REQUEST_RESUME = 12
        private const val REQUEST_RESUME_VIA_APP = 13
    }
}
