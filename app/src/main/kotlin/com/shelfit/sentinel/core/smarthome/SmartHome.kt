package com.shelfit.sentinel.core.smarthome

/**
 * The app's own vocabulary for a smart home.
 *
 * Deliberately not Google's. Every type here is defined in terms of what this app needs —
 * a handful of switchable devices grouped into homes — so that exactly one file in the
 * codebase ever mentions a vendor SDK. That is what keeps the microphone and clap modules
 * free of any smart-home dependency, and what would let a second provider be added
 * without touching the rule layer.
 *
 * Kept small on purpose: lights and outlets, on and off. A richer device model can grow
 * here when something needs it.
 */
@JvmInline
value class StructureId(val value: String) {
    override fun toString(): String = value
}

/** A device that can be switched. */
@JvmInline
value class DeviceId(val value: String) {
    override fun toString(): String = value
}

/** A "Home" in Google's terms: the grouping a user picks between. */
data class SmartHomeStructure(
    val id: StructureId,
    val name: String,
)

/**
 * What kind of thing a device is, as far as this app cares.
 *
 * Anything that is not a light or an outlet is [UNSUPPORTED] — not hidden, but not
 * selectable either, because guessing at how to switch an unfamiliar device is how an
 * automation ends up doing something surprising.
 */
enum class DeviceKind {
    LIGHT,
    OUTLET,
    UNSUPPORTED,
}

/**
 * One device as the app sees it.
 *
 * @param reachable false when the provider reports the device offline. Kept rather than
 *   filtered out: a user who cannot find their lamp in the list is worse off than one who
 *   sees it greyed out and knows why.
 * @param isOn null when the device does not report its state. That is precisely the case
 *   where [SmartHomeCommand.TOGGLE] cannot work, so it is modelled rather than assumed.
 */
data class SmartHomeDevice(
    val id: DeviceId,
    val name: String,
    val roomName: String?,
    val kind: DeviceKind,
    val reachable: Boolean,
    val isOn: Boolean?,
) {
    val supported: Boolean get() = kind != DeviceKind.UNSUPPORTED

    /** Toggle needs a known starting state; on and off do not. */
    val stateKnown: Boolean get() = isOn != null

    /** Whether this device can be put in an automation at all. */
    val selectable: Boolean get() = supported
}

enum class SmartHomeCommand {
    ON,
    OFF,

    /** Requires [SmartHomeDevice.stateKnown] for every target. */
    TOGGLE,
    ;

    val label: String
        get() = when (this) {
            ON -> "Turn on"
            OFF -> "Turn off"
            TOGGLE -> "Toggle"
        }
}

/**
 * Why something did not work, in terms the UI can explain to a person.
 *
 * A flat kind plus optional context rather than a deep hierarchy: the UI needs a sentence
 * and a suggested fix, and every one of these maps to one of each.
 */
data class SmartHomeFailure(
    val kind: Kind,
    val deviceName: String? = null,
    val detail: String? = null,
) {
    enum class Kind {
        /** No SDK configured in this build. A developer problem, not a user one. */
        NOT_CONFIGURED,

        /** The user has not linked their Google account yet. */
        NOT_CONNECTED,

        /** Linked once, but the permission has since been withdrawn. */
        PERMISSION_DENIED,

        NETWORK_UNAVAILABLE,

        /** Reached the provider, but it could not serve the home. */
        HOME_UNAVAILABLE,

        /** The device is known but not responding. */
        DEVICE_OFFLINE,

        /** The device is no longer in the home — renamed, replaced or deleted. */
        DEVICE_REMOVED,

        /** Not a light or an outlet, so this app will not try to switch it. */
        DEVICE_UNSUPPORTED,

        /** Toggle was asked for but the device does not report whether it is on. */
        DEVICE_STATE_UNKNOWN,

        /** The command reached the device and was refused. */
        COMMAND_REJECTED,

        UNKNOWN,
    }

    /**
     * Whether a person has to do something, as opposed to the app retrying or waiting.
     *
     * Drives whether the UI nags. An offline lamp will probably be back; a withdrawn
     * permission will not fix itself.
     */
    val needsUserAction: Boolean
        get() = when (kind) {
            Kind.NOT_CONFIGURED,
            Kind.NOT_CONNECTED,
            Kind.PERMISSION_DENIED,
            Kind.DEVICE_REMOVED,
            Kind.DEVICE_UNSUPPORTED,
            Kind.DEVICE_STATE_UNKNOWN,
            -> true

            Kind.NETWORK_UNAVAILABLE,
            Kind.HOME_UNAVAILABLE,
            Kind.DEVICE_OFFLINE,
            Kind.COMMAND_REJECTED,
            Kind.UNKNOWN,
            -> false
        }
}

/** Where the connection to the provider stands. */
sealed interface SmartHomeState {

    /**
     * This build has no smart-home SDK wired in.
     *
     * The honest state for a build where the vendor SDK is not present. Reported rather
     * than pretended away, so the UI can say so instead of failing mysteriously.
     */
    data object NotConfigured : SmartHomeState

    /** Configured, but the user has not linked an account. */
    data object NotConnected : SmartHomeState

    /** Linked previously; the permission needs granting again. */
    data object PermissionRequired : SmartHomeState

    /** Linked and usable. [structures] is what the user picks a home from. */
    data class Connected(
        val structures: List<SmartHomeStructure>,
        val selected: StructureId? = structures.firstOrNull()?.id,
    ) : SmartHomeState

    /** Reached the provider and it went wrong. */
    data class Unavailable(val failure: SmartHomeFailure) : SmartHomeState

    val isConnected: Boolean get() = this is Connected
}

/** Outcome for a single device. */
data class DeviceCommandResult(
    val deviceId: DeviceId,
    val deviceName: String,
    /** null on success. */
    val failure: SmartHomeFailure?,
) {
    val succeeded: Boolean get() = failure == null
}

/**
 * Outcome of one command across every selected device.
 *
 * Per-device rather than a single verdict, because a rule pointing at three lamps where
 * one is unplugged should say so — "2 of 3 switched" is useful, "failed" is not.
 */
data class DeviceCommandReport(
    val results: List<DeviceCommandResult>,
) {
    val succeeded: List<DeviceCommandResult> get() = results.filter { it.succeeded }
    val failed: List<DeviceCommandResult> get() = results.filterNot { it.succeeded }

    val allSucceeded: Boolean get() = results.isNotEmpty() && failed.isEmpty()
    val allFailed: Boolean get() = results.isNotEmpty() && succeeded.isEmpty()
    val partial: Boolean get() = succeeded.isNotEmpty() && failed.isNotEmpty()

    /** One line naming what went wrong, for a notification or the activity log. */
    fun summarise(): String = failed
        .joinToString(", ") { result ->
            "${result.deviceName}: ${result.failure?.kind?.describe() ?: "failed"}"
        }
        .ifEmpty { "all devices switched" }

    companion object {
        /** Same failure for every target — used when nothing could even be attempted. */
        fun allFailing(
            targets: List<Pair<DeviceId, String>>,
            failure: SmartHomeFailure,
        ): DeviceCommandReport = DeviceCommandReport(
            targets.map { (id, name) -> DeviceCommandResult(id, name, failure) },
        )
    }
}

fun SmartHomeFailure.Kind.describe(): String = when (this) {
    SmartHomeFailure.Kind.NOT_CONFIGURED -> "smart home not set up in this build"
    SmartHomeFailure.Kind.NOT_CONNECTED -> "Google Home not connected"
    SmartHomeFailure.Kind.PERMISSION_DENIED -> "permission withdrawn"
    SmartHomeFailure.Kind.NETWORK_UNAVAILABLE -> "no network"
    SmartHomeFailure.Kind.HOME_UNAVAILABLE -> "home unavailable"
    SmartHomeFailure.Kind.DEVICE_OFFLINE -> "offline"
    SmartHomeFailure.Kind.DEVICE_REMOVED -> "no longer in the home"
    SmartHomeFailure.Kind.DEVICE_UNSUPPORTED -> "not a supported device"
    SmartHomeFailure.Kind.DEVICE_STATE_UNKNOWN -> "cannot tell if it is on"
    SmartHomeFailure.Kind.COMMAND_REJECTED -> "refused the command"
    SmartHomeFailure.Kind.UNKNOWN -> "unknown error"
}
