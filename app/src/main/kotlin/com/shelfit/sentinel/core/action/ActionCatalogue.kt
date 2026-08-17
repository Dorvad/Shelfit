package com.shelfit.sentinel.core.action

/**
 * One kind of action the rule editor can offer.
 *
 * [template] doubles as the instance a rule gets: actions are immutable data, so there
 * is nothing to copy. When an action first needs per-rule parameters — a smart-home
 * action naming a device, say — this is where a factory and the editor's fields belong,
 * and the persisted rule gains a parameters map. Inventing that machinery before
 * anything needs it would be guessing at the shape.
 *
 * [type] and [displayName] deliberately come from the template rather than being
 * declared again here, so the persisted key and the label have one definition.
 */
class ActionKind(
    val template: Action,
    /** One line explaining what the action does, for the editor. */
    val description: String,
    /**
     * True when the action does nothing until the user configures it — choosing devices,
     * say. The editor uses this to know it must not let the rule be saved half-finished.
     */
    val requiresConfiguration: Boolean = false,
    /**
     * Rebuilds the action from stored parameters. Defaults to ignoring them, which is right
     * for every action that has nothing to configure.
     */
    private val factory: (Map<String, String>) -> Action = { template },
) {
    val type: String get() = template.type
    val displayName: String get() = template.displayName

    /** The action to attach to a rule, given whatever the user configured. */
    fun create(parameters: Map<String, String> = emptyMap()): Action = factory(parameters)
}

/**
 * The actions this build can perform, and the bridge between a persisted action type
 * and a usable [Action].
 *
 * Registered in `AppContainer` from the same place as the executors, so a kind offered
 * in the editor always has something able to perform it — an invariant the container
 * checks at startup rather than leaving to be discovered by a user.
 */
class ActionCatalogue(kinds: List<ActionKind>) {

    val kinds: List<ActionKind> = kinds.toList()

    private val byType: Map<String, ActionKind> = kinds.associateBy { it.type }

    init {
        require(byType.size == kinds.size) { "Duplicate action type in catalogue" }
    }

    fun kind(type: String): ActionKind? = byType[type]

    /**
     * Rebuilds an action from a persisted type and its parameters, or null if this build
     * does not know the type.
     *
     * A rule referring to an unknown action is kept rather than discarded — the app may
     * have been downgraded, or the rule written by a later version — and shows in the
     * editor as having no action.
     */
    fun action(type: String?, parameters: Map<String, String> = emptyMap()): Action? =
        type?.let { byType[it]?.create(parameters) }

    companion object {

        /** Actions that run entirely on the phone. Always available. */
        private val localKinds = listOf(
            ActionKind(
                template = VibrateAction(),
                description = "A short buzz. Works with the screen off and the phone " +
                    "in a pocket.",
            ),
            ActionKind(
                template = ShowNotificationAction,
                description = "A notification naming the trigger. Useful when the " +
                    "phone is across the room.",
            ),
            ActionKind(
                template = DebugLogAction,
                description = "Writes a line to logcat. Leaves no trace on the device.",
            ),
        )

        private val smartHomeKind = ActionKind(
            template = SmartHomeDeviceAction(),
            description = "Turn lights or smart plugs on, off, or toggle them.",
            requiresConfiguration = true,
            factory = SmartHomeDeviceAction::fromParameters,
        )

        /** Local actions only — the catalogue for a build with no smart-home provider. */
        val LocalDebug = ActionCatalogue(localKinds)

        /**
         * Local actions plus smart-home control.
         *
         * Offered whenever a `SmartHomeClient` is wired in, even one that is not yet
         * connected: a user needs to be able to build the automation before linking the
         * account, and the executor reports "not connected" clearly if they run it early.
         */
        val WithSmartHome = ActionCatalogue(localKinds + smartHomeKind)
    }
}
