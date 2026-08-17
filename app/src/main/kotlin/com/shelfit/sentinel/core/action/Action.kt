package com.shelfit.sentinel.core.action

import com.shelfit.sentinel.core.smarthome.DeviceId
import com.shelfit.sentinel.core.smarthome.SmartHomeCommand

/**
 * Something to do when a rule fires. Pure data — an [ActionExecutor] performs it.
 *
 * Splitting the description from the execution is what lets a smart-home action be
 * added later without the rule layer, or any detector, learning anything about
 * smart-home APIs. A detector emits a [com.shelfit.sentinel.core.trigger.TriggerEvent];
 * it has no reference to this file and must never acquire one.
 */
interface Action {
    /** Stable type key. Persisted in rules, and how an executor recognises its own work. */
    val type: String

    /** Shown in the UI. May describe this instance, not just its kind. */
    val displayName: String

    /**
     * Everything needed to rebuild this action, as plain strings.
     *
     * Empty for actions with nothing to configure. [ActionKind] is the other half: it turns
     * a map back into an action. Keeping persistence as flat strings rather than a
     * serialised object means an older build can read a rule it does not fully understand
     * and keep the parts it does.
     */
    val parameters: Map<String, String> get() = emptyMap()
}

/**
 * Writes the detection to logcat.
 *
 * The cheapest possible proof that an event reached the action layer, and worth keeping
 * once real actions exist.
 */
data object DebugLogAction : Action {
    override val type: String = "debug.log"
    override val displayName: String = "Write to log"
}

/**
 * Buzzes the device.
 *
 * @param durationMillis not user-editable yet; see [ActionKind] for where per-rule
 *   parameters will go when an action needs them.
 */
data class VibrateAction(
    val durationMillis: Long = 140L,
) : Action {
    override val type: String = "debug.vibrate"
    override val displayName: String = "Vibrate the phone"
}

/**
 * Posts a notification naming the trigger that fired.
 *
 * Local feedback, like the other two — nothing leaves the device. Useful when the phone
 * is across the room and a vibration would go unnoticed.
 */
data object ShowNotificationAction : Action {
    override val type: String = "debug.notification"
    override val displayName: String = "Show a notification"
}

/**
 * Switches one or more smart-home devices.
 *
 * The first action with per-rule configuration, and the reason [Action.parameters] exists.
 * It names devices by id and carries the name each had when it was chosen, so a rule can be
 * listed and explained without a live connection — and so a failure can be reported against
 * something the user recognises even after the device has been removed.
 *
 * Note what this type does *not* contain: no vendor SDK type, no account, no network. It is
 * a list of identifiers and a verb. `SmartHomeActionExecutor` and the `SmartHomeClient`
 * behind it are what turn it into an actual command.
 */
data class SmartHomeDeviceAction(
    val targets: List<SmartHomeTarget> = emptyList(),
    val command: SmartHomeCommand = SmartHomeCommand.TOGGLE,
) : Action {

    override val type: String = TYPE

    override val displayName: String
        get() = when {
            targets.isEmpty() -> "Control smart-home devices"
            targets.size == 1 -> "${command.label} ${targets.single().name}"
            else -> "${command.label} ${targets.size} devices"
        }

    override val parameters: Map<String, String>
        get() = buildMap {
            put(KEY_COMMAND, command.name)
            targets.forEachIndexed { index, target ->
                put("$KEY_DEVICE_ID.$index", target.deviceId.value)
                put("$KEY_DEVICE_NAME.$index", target.name)
            }
        }

    /** A rule using this action does nothing useful until devices have been chosen. */
    val configured: Boolean get() = targets.isNotEmpty()

    companion object {
        const val TYPE: String = "smarthome.device_command"

        private const val KEY_COMMAND = "command"
        private const val KEY_DEVICE_ID = "device"
        private const val KEY_DEVICE_NAME = "name"

        /**
         * Rebuilds the action from stored parameters.
         *
         * Forgiving by design: an unrecognised command falls back to the default rather than
         * discarding the user's device list, and device entries are read in index order so a
         * gap left by a partial write does not silently reorder anything.
         */
        fun fromParameters(parameters: Map<String, String>): SmartHomeDeviceAction {
            val command = SmartHomeCommand.entries
                .firstOrNull { it.name == parameters[KEY_COMMAND] }
                ?: SmartHomeCommand.TOGGLE

            val targets = parameters.keys
                .mapNotNull { key -> key.removePrefixOrNull("$KEY_DEVICE_ID.")?.toIntOrNull() }
                .sorted()
                .mapNotNull { index ->
                    val id = parameters["$KEY_DEVICE_ID.$index"] ?: return@mapNotNull null
                    if (id.isEmpty()) return@mapNotNull null
                    SmartHomeTarget(
                        deviceId = DeviceId(id),
                        name = parameters["$KEY_DEVICE_NAME.$index"].orEmpty()
                            .ifEmpty { "Unknown device" },
                    )
                }

            return SmartHomeDeviceAction(targets = targets, command = command)
        }

        private fun String.removePrefixOrNull(prefix: String): String? =
            if (startsWith(prefix)) removePrefix(prefix) else null
    }
}

/**
 * A device a rule points at, with the name it had when chosen.
 *
 * The name is a display cache, not a source of truth — it can go stale if the user renames
 * the device in Google Home. That is an acceptable trade for being able to show a rule
 * without a network round trip.
 */
data class SmartHomeTarget(
    val deviceId: DeviceId,
    val name: String,
)
