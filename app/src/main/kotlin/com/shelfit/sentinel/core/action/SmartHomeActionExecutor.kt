package com.shelfit.sentinel.core.action

import com.shelfit.sentinel.core.smarthome.DeviceCommandReport
import com.shelfit.sentinel.core.smarthome.SmartHomeClient
import com.shelfit.sentinel.core.smarthome.SmartHomeState
import com.shelfit.sentinel.core.smarthome.describe
import com.shelfit.sentinel.core.trigger.TriggerEvent

/**
 * Performs [SmartHomeDeviceAction] by asking a [SmartHomeClient].
 *
 * Named for the capability rather than the provider, and deliberately: it holds no vendor
 * type, so Google Home is one `SmartHomeClient` behind it rather than something this class
 * knows about. Adding a second provider means another client, not another executor.
 *
 * It is in `core/` because it has no Android dependency — unusual for an executor, and the
 * direct consequence of the vendor SDK living behind an interface. That also makes every
 * failure path below testable without a device or an account.
 *
 * Every condition the automation layer can meet is a returned [ActionResult], never an
 * exception: an unreachable lamp must not take down a rule engine that a foreground service
 * depends on.
 */
class SmartHomeActionExecutor(
    private val client: SmartHomeClient,
) : ActionExecutor {

    override fun canExecute(action: Action): Boolean = action is SmartHomeDeviceAction

    override suspend fun execute(action: Action, event: TriggerEvent): ActionResult {
        val request = action as? SmartHomeDeviceAction
            ?: return ActionResult.Skipped("Not a smart-home action")

        if (!request.configured) {
            return ActionResult.Skipped("No devices chosen for this automation")
        }

        // Check the connection before sending anything. Otherwise a disconnected account
        // reports as several device failures, which reads like a hardware problem.
        connectionRefusal()?.let { return it }

        val targets = request.targets.map { it.deviceId to it.name }
        val report = client.execute(request.command, targets)

        return report.toActionResult(request)
    }

    /**
     * The reason nothing should be attempted, or null to go ahead.
     *
     * One clear message beats a list of device errors that all say the same thing.
     */
    private fun connectionRefusal(): ActionResult? = when (val state = client.state.value) {
        SmartHomeState.NotConfigured ->
            ActionResult.Skipped("Smart home is not set up in this build")

        SmartHomeState.NotConnected ->
            ActionResult.Skipped("Google Home is not connected")

        SmartHomeState.PermissionRequired ->
            ActionResult.Failure("Google Home permission was withdrawn — reconnect in Settings")

        is SmartHomeState.Unavailable ->
            ActionResult.Failure("Smart home unavailable: ${state.failure.kind.describe()}")

        is SmartHomeState.Connected -> null
    }

    private fun DeviceCommandReport.toActionResult(
        request: SmartHomeDeviceAction,
    ): ActionResult = when {
        results.isEmpty() ->
            ActionResult.Failure("No devices were reachable")

        allSucceeded -> ActionResult.Success

        allFailed -> ActionResult.Failure(
            "${request.command.label} failed: ${summarise()}",
        )

        else -> ActionResult.Partial(
            succeeded = succeeded.size,
            failed = failed.size,
            message = "${succeeded.size} of ${results.size} switched — ${summarise()}",
        )
    }
}
