package com.shelfit.sentinel.core.sensormode

/**
 * What the user has asked for. Persisted, so it survives process death, upgrades and
 * reboots.
 *
 * Kept separate from what is actually happening: Android can refuse to run a
 * microphone foreground service at times of its choosing, and the difference between
 * "the user wants this on" and "it is on" is exactly what the health screen exists to
 * show.
 */
enum class ListeningMode {
    /** Sensor Mode is off. No service, no microphone. */
    OFF,

    /** Sensor Mode is on and should be listening. */
    LISTENING,

    /**
     * Sensor Mode is on but the microphone is deliberately released. The service stays
     * in the foreground so the notification can offer Resume, which is the only way
     * back that does not require reopening the app.
     */
    PAUSED,
    ;

    val isEnabled: Boolean get() = this != OFF

    companion object {
        val Default = OFF

        fun fromName(name: String?): ListeningMode =
            entries.firstOrNull { it.name == name } ?: Default
    }
}

/** Whether something the app needs has been granted. */
enum class PermissionStatus {
    GRANTED,
    DENIED,

    /** Not required on this Android version. */
    NOT_REQUIRED,
    ;

    val isSatisfied: Boolean get() = this != DENIED
}

/** Whether the system may curtail the app's background work. */
enum class BatteryOptimisationStatus {
    /** The app is exempt; the system will leave it alone. */
    EXEMPT,

    /** The app is subject to optimisation. Usually fine, but OEM behaviour varies. */
    OPTIMISED,

    /** Could not be determined on this device. */
    UNKNOWN,
}

/** Something that went wrong and may need a person to look at it. */
data class SensorModeError(
    val kind: Kind,
    val atEpochMillis: Long,
    val detail: String? = null,
) {
    enum class Kind {
        /** The microphone permission was taken away while Sensor Mode was on. */
        MICROPHONE_PERMISSION_REVOKED,

        /** Another app holds the microphone, or the device refused to open it. */
        MICROPHONE_UNAVAILABLE,

        /** Android would not let the foreground service start right now. */
        FOREGROUND_START_BLOCKED,

        /** Capture failed for some other reason. */
        AUDIO_FAILURE,
    }

    /** True when the user has to do something; false when the app will retry alone. */
    val needsUserAction: Boolean
        get() = when (kind) {
            Kind.MICROPHONE_PERMISSION_REVOKED, Kind.FOREGROUND_START_BLOCKED -> true
            Kind.MICROPHONE_UNAVAILABLE, Kind.AUDIO_FAILURE -> false
        }
}

/**
 * Everything the Sensor Health screen needs, and the basis for every decision about
 * whether unattended operation will actually work.
 *
 * Pure data with no Android types, so the derivations below are unit-testable.
 */
data class SensorHealth(
    val desiredMode: ListeningMode = ListeningMode.Default,
    val serviceRunning: Boolean = false,
    val listening: Boolean = false,
    val microphone: PermissionStatus = PermissionStatus.DENIED,
    val notifications: PermissionStatus = PermissionStatus.DENIED,
    val batteryOptimisation: BatteryOptimisationStatus = BatteryOptimisationStatus.UNKNOWN,
    val lastServiceStartAtEpochMillis: Long? = null,
    val lastTriggerAtEpochMillis: Long? = null,
    val lastBootAtEpochMillis: Long? = null,
    val lastError: SensorModeError? = null,
) {

    /**
     * The user wants Sensor Mode on but nothing is running.
     *
     * This is the state after a reboot, after an app upgrade, and after the process is
     * killed — Android does not let an app start a microphone foreground service from
     * the background, so all three land here and all three need one tap to recover.
     */
    val resumeRequired: Boolean
        get() = desiredMode == ListeningMode.LISTENING && !serviceRunning

    /** Nothing is stopping unattended operation. */
    val readyForUnattendedUse: Boolean
        get() = microphone == PermissionStatus.GRANTED &&
            notifications.isSatisfied &&
            !resumeRequired &&
            lastError?.needsUserAction != true

    /** Ordered worst-first, so the UI can lead with whatever matters most. */
    val attention: List<Attention>
        get() = buildList {
            if (microphone != PermissionStatus.GRANTED) add(Attention.MICROPHONE_PERMISSION)
            if (resumeRequired) add(Attention.RESUME_REQUIRED)
            if (lastError?.needsUserAction == true) add(Attention.ERROR)
            if (notifications == PermissionStatus.DENIED) add(Attention.NOTIFICATION_PERMISSION)
            if (batteryOptimisation == BatteryOptimisationStatus.OPTIMISED) {
                add(Attention.BATTERY_OPTIMISATION)
            }
        }

    enum class Attention {
        MICROPHONE_PERMISSION,
        RESUME_REQUIRED,
        ERROR,
        NOTIFICATION_PERMISSION,
        BATTERY_OPTIMISATION,
    }
}
