package com.shelfit.sentinel.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.shelfit.sentinel.MainActivity
import com.shelfit.sentinel.R
import com.shelfit.sentinel.core.action.Action
import com.shelfit.sentinel.core.action.ActionExecutor
import com.shelfit.sentinel.core.action.ActionResult
import com.shelfit.sentinel.core.action.ShowNotificationAction
import com.shelfit.sentinel.core.trigger.TriggerEvent
import com.shelfit.sentinel.core.trigger.TriggerRegistry

/**
 * Executes [ShowNotificationAction].
 *
 * Its own channel, separate from Sensor Mode's status and alert channels: a user who
 * wants to know the phone is listening without being pinged on every detection should be
 * able to silence one and not the other.
 *
 * Each firing replaces the previous notification rather than stacking, which is what a
 * debug action wants — the interesting thing is the most recent detection.
 *
 * @param registry used only to name the trigger in the notification text. The action
 *   layer never learns what a trigger *is*, only what it is called.
 */
class NotificationActionExecutor(
    context: Context,
    private val registry: TriggerRegistry,
) : ActionExecutor {

    private val appContext = context.applicationContext
    private val manager = NotificationManagerCompat.from(appContext)

    private var channelReady = false

    override fun canExecute(action: Action): Boolean = action is ShowNotificationAction

    override suspend fun execute(action: Action, event: TriggerEvent): ActionResult {
        if (!manager.areNotificationsEnabled()) {
            return ActionResult.Skipped("Notifications are turned off")
        }

        ensureChannel()

        val triggerName = registry.trigger(event.triggerId)?.displayName
            ?: event.triggerId.value

        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(appContext.getString(R.string.action_notification_title))
                .setContentText(
                    appContext.getString(
                        R.string.action_notification_text,
                        triggerName,
                        (event.confidence * PERCENT).toInt(),
                    ),
                )
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .build(),
        )
        return ActionResult.Success
    }

    private fun ensureChannel() {
        if (channelReady) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                appContext.getString(R.string.channel_automation_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = appContext.getString(R.string.channel_automation_description)
            },
        )
        channelReady = true
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        appContext,
        REQUEST_OPEN_APP,
        Intent(appContext, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val CHANNEL_ID = "automation_events"
        const val NOTIFICATION_ID = 3
        const val REQUEST_OPEN_APP = 20
        const val PERCENT = 100f
    }
}
