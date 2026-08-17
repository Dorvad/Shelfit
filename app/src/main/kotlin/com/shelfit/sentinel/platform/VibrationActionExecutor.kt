package com.shelfit.sentinel.platform

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.ContextCompat
import com.shelfit.sentinel.core.action.Action
import com.shelfit.sentinel.core.action.ActionExecutor
import com.shelfit.sentinel.core.action.ActionResult
import com.shelfit.sentinel.core.action.VibrateAction
import com.shelfit.sentinel.core.trigger.TriggerEvent

/**
 * Performs [VibrateAction].
 *
 * @param isEnabled consulted per execution rather than captured once, so turning
 *   haptics off in settings takes effect immediately without restarting detection.
 */
class VibrationActionExecutor(
    context: Context,
    private val isEnabled: suspend () -> Boolean,
) : ActionExecutor {

    private val appContext = context.applicationContext

    override fun canExecute(action: Action): Boolean = action is VibrateAction

    override suspend fun execute(action: Action, event: TriggerEvent): ActionResult {
        val vibrate = action as? VibrateAction
            ?: return ActionResult.Skipped("Not a vibrate action")

        if (!isEnabled()) return ActionResult.Skipped("Haptic feedback is turned off")

        val vibrator = vibrator() ?: return ActionResult.Skipped("No vibrator on this device")
        if (!vibrator.hasVibrator()) return ActionResult.Skipped("No vibrator on this device")

        vibrator.vibrate(
            VibrationEffect.createOneShot(
                vibrate.durationMillis,
                VibrationEffect.DEFAULT_AMPLITUDE,
            ),
        )
        return ActionResult.Success
    }

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.getSystemService(appContext, VibratorManager::class.java)?.defaultVibrator
        } else {
            ContextCompat.getSystemService(appContext, Vibrator::class.java)
        }
}
