package com.shelfit.sentinel.core.smarthome

/**
 * Which smart home the app should talk to.
 *
 * Names providers without knowing anything about them — each one is a [SmartHomeClient]
 * implementation in `platform/smarthome/`, and this enum is only the user's choice between
 * them. Adding a provider means adding a case here and one implementation, and nothing else.
 */
enum class SmartHomeProvider(
    val label: String,
    /** One line for the chooser, in the user's terms rather than the API's. */
    val description: String,
) {
    /**
     * No smart home. The honest default: a fresh install controls nothing until the user
     * says otherwise.
     */
    NONE(
        label = "None",
        description = "Automations can only do things on this phone.",
    ),

    TUYA(
        label = "Tuya / Smart Life",
        description = "Lights and plugs in the Smart Life or Tuya Smart app. " +
            "Needs a free Tuya developer project.",
    ),

    /**
     * Reports "not configured" until the Home APIs SDK is in the build. See
     * `docs/google-home-setup.md`.
     */
    GOOGLE(
        label = "Google Home",
        description = "Not available in this build — the Google SDK is not included.",
    ),

    SIMULATED(
        label = "Simulated home",
        description = "Pretend devices for trying automations out. Touches no real hardware.",
    ),
    ;

    companion object {
        val Default = NONE

        fun fromName(name: String?): SmartHomeProvider =
            entries.firstOrNull { it.name == name } ?: Default
    }
}
