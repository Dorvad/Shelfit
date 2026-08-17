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
) {
    val type: String get() = template.type
    val displayName: String get() = template.displayName

    /** The action to attach to a rule. */
    fun create(): Action = template
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
     * Rebuilds an action from a persisted type, or null if this build does not know it.
     *
     * A rule referring to an unknown action is kept rather than discarded — the app may
     * have been downgraded, or the rule written by a later version — and shows in the
     * editor as having no action.
     */
    fun action(type: String?): Action? = type?.let { byType[it]?.create() }

    companion object {
        /**
         * Local actions only: they prove the pipeline end to end without involving
         * anything outside the phone.
         */
        val LocalDebug = ActionCatalogue(
            listOf(
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
            ),
        )
    }
}
