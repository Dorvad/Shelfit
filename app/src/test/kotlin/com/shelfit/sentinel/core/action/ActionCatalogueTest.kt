package com.shelfit.sentinel.core.action

import com.shelfit.sentinel.core.trigger.TriggerEvent
import com.shelfit.sentinel.core.trigger.TriggerId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionCatalogueTest {

    private val catalogue = ActionCatalogue.LocalDebug

    @Test
    fun `the local debug catalogue offers the three local actions`() {
        assertEquals(
            setOf(VibrateAction().type, ShowNotificationAction.type, DebugLogAction.type),
            catalogue.kinds.map { it.type }.toSet(),
        )
    }

    @Test
    fun `a persisted type rebuilds the action it names`() {
        assertEquals(VibrateAction(), catalogue.action(VibrateAction().type))
        assertEquals(ShowNotificationAction, catalogue.action(ShowNotificationAction.type))
        assertEquals(DebugLogAction, catalogue.action(DebugLogAction.type))
    }

    @Test
    fun `an unknown type yields no action rather than throwing`() {
        assertNull(catalogue.action("smarthome.toggle"))
        assertNull(catalogue.action(null))
    }

    @Test
    fun `type and label come from the action, so they cannot drift apart`() {
        catalogue.kinds.forEach { kind ->
            assertEquals(kind.template.type, kind.type)
            assertEquals(kind.template.displayName, kind.displayName)
        }
    }

    @Test
    fun `every kind carries a description for the editor`() {
        assertTrue(catalogue.kinds.all { it.description.isNotBlank() })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `duplicate action types are rejected at construction`() {
        ActionCatalogue(
            listOf(
                ActionKind(VibrateAction(), "one"),
                ActionKind(VibrateAction(durationMillis = 500L), "another with the same type"),
            ),
        )
    }

    /**
     * The invariant `AppContainer` asserts at startup: an action the editor offers with no
     * executor behind it would be a rule that silently does nothing.
     */
    @Test
    fun `an unsupported action is detectable before it reaches a user`() {
        val dispatcher = ActionDispatcher(
            listOf(
                object : ActionExecutor {
                    override fun canExecute(action: Action) = action is VibrateAction
                    override suspend fun execute(action: Action, event: TriggerEvent) =
                        ActionResult.Success
                },
            ),
        )

        val unsupported = catalogue.kinds.filterNot { dispatcher.supports(it.template) }

        assertEquals(
            setOf(ShowNotificationAction.type, DebugLogAction.type),
            unsupported.map { it.type }.toSet(),
        )
        assertTrue(dispatcher.supports(VibrateAction()))
    }

    @Test
    fun `no action type collides with another`() {
        val types = catalogue.kinds.map { it.type }

        assertEquals(types.size, types.distinct().size)
    }

    @Test
    fun `reserved trigger identifiers are distinct from the implemented one`() {
        val reserved = listOf(
            TriggerId.CameraMotion,
            TriggerId.HandGesture,
            TriggerId.AmbientLight,
            TriggerId.DeviceMovement,
        )

        assertTrue(reserved.none { it == TriggerId.DoubleClap })
        assertEquals(reserved.size, reserved.distinct().size)
    }
}
