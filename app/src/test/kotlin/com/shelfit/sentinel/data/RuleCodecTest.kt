package com.shelfit.sentinel.data

import com.shelfit.sentinel.core.action.ActionCatalogue
import com.shelfit.sentinel.core.action.ShowNotificationAction
import com.shelfit.sentinel.core.action.SmartHomeDeviceAction
import com.shelfit.sentinel.core.action.SmartHomeTarget
import com.shelfit.sentinel.core.action.VibrateAction
import com.shelfit.sentinel.core.rule.AutomationRule
import com.shelfit.sentinel.core.smarthome.DeviceId
import com.shelfit.sentinel.core.smarthome.SmartHomeCommand
import com.shelfit.sentinel.core.trigger.TriggerId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rule persistence.
 *
 * The interesting cases are the unhappy ones: a hand-written codec earns its keep only if
 * it survives text it did not expect, and losing a user's rules to a parse failure would
 * be a far worse bug than showing one as unavailable.
 */
class RuleCodecTest {

    private val catalogue = ActionCatalogue.LocalDebug

    private fun rule(
        id: String = "rule.1",
        name: String = "Double clap → Vibrate the phone",
        triggerId: TriggerId = TriggerId.DoubleClap,
        action: com.shelfit.sentinel.core.action.Action? = VibrateAction(),
        enabled: Boolean = true,
        minimumConfidence: Float = 0f,
        cooldownMillis: Long = 1_500L,
    ) = AutomationRule(id, name, triggerId, action, enabled, minimumConfidence, cooldownMillis)

    private fun roundTrip(rules: List<AutomationRule>): List<AutomationRule>? =
        RuleCodec.decode(RuleCodec.encode(rules), catalogue)

    @Test
    fun `a rule survives a round trip`() {
        val original = rule()

        assertEquals(listOf(original), roundTrip(listOf(original)))
    }

    @Test
    fun `every field survives a round trip`() {
        val original = rule(
            id = "rule.abc",
            name = "Custom",
            action = ShowNotificationAction,
            enabled = false,
            minimumConfidence = 0.75f,
            cooldownMillis = 9_999L,
        )

        val decoded = roundTrip(listOf(original))!!.single()

        assertEquals(original.id, decoded.id)
        assertEquals(original.name, decoded.name)
        assertEquals(original.triggerId, decoded.triggerId)
        assertEquals(original.action, decoded.action)
        assertEquals(original.enabled, decoded.enabled)
        assertEquals(original.minimumConfidence, decoded.minimumConfidence, 1e-6f)
        assertEquals(original.cooldownMillis, decoded.cooldownMillis)
    }

    @Test
    fun `order is preserved`() {
        val rules = listOf(rule(id = "a"), rule(id = "b"), rule(id = "c"))

        assertEquals(listOf("a", "b", "c"), roundTrip(rules)!!.map { it.id })
    }

    @Test
    fun `a name containing the field separator does not corrupt the record`() {
        val awkward = rule(name = "a|b|c")

        assertEquals(awkward.name, roundTrip(listOf(awkward))!!.single().name)
    }

    @Test
    fun `a name containing newlines does not split into two records`() {
        val awkward = rule(name = "line one\nline two")

        val decoded = roundTrip(listOf(awkward))!!
        assertEquals(1, decoded.size)
        assertEquals(awkward.name, decoded.single().name)
    }

    @Test
    fun `an empty rule list is distinguishable from a device never configured`() {
        // The difference decides whether defaults get re-seeded over a deliberate choice.
        assertEquals(emptyList<AutomationRule>(), RuleCodec.decode(RuleCodec.encode(emptyList()), catalogue))
        assertNull("absent means never configured", RuleCodec.decode(null, catalogue))
        assertNull(RuleCodec.decode("", catalogue))
    }

    @Test
    fun `an unknown format version is treated as never configured`() {
        assertNull(RuleCodec.decode("v99\nsomething", catalogue))
    }

    @Test
    fun `a malformed line is skipped without losing the rest`() {
        val good = RuleCodec.encode(listOf(rule(id = "keep")))
        val corrupted = good + "\nnot-a-rule\n|||||"

        val decoded = RuleCodec.decode(corrupted, catalogue)!!

        assertEquals(listOf("keep"), decoded.map { it.id })
    }

    @Test
    fun `a rule naming an unknown action is kept with no action rather than dropped`() {
        // What a downgrade looks like: the rule was written when a smart-home action
        // existed. Discarding it would silently delete the user's configuration.
        val stored = RuleCodec.encode(listOf(rule()))
            .replace(VibrateAction().type, "smarthome.toggle")

        val decoded = RuleCodec.decode(stored, catalogue)!!.single()

        assertEquals("rule.1", decoded.id)
        assertNull("the action cannot be rebuilt", decoded.action)
    }

    @Test
    fun `a rule naming an unimplemented trigger is kept`() {
        // Reserved identifiers have no detector, but a rule pointing at one must survive
        // so the editor can show it as unavailable.
        val stored = RuleCodec.encode(listOf(rule(triggerId = TriggerId.CameraMotion)))

        val decoded = RuleCodec.decode(stored, catalogue)!!.single()

        assertEquals(TriggerId.CameraMotion, decoded.triggerId)
    }

    @Test
    fun `a rule with no action round trips as having no action`() {
        val decoded = roundTrip(listOf(rule(action = null)))!!.single()

        assertNull(decoded.action)
    }

    @Test
    fun `a record missing its identifier is skipped`() {
        val stored = "v1\n|name|audio.double_clap|debug.vibrate|true|0.0|0"

        assertTrue(RuleCodec.decode(stored, catalogue)!!.isEmpty())
    }

    /**
     * Action parameters, added in v2.
     *
     * The device list a user assembled is the most laborious thing they configure, so it has
     * to survive both a round trip and an upgrade from a format that never stored it.
     */
    @Test
    fun `a smart-home action keeps its devices and command across a round trip`() {
        val withSmartHome = ActionCatalogue.WithSmartHome
        val action = SmartHomeDeviceAction(
            targets = listOf(
                SmartHomeTarget(DeviceId("lamp"), "Living room lamp"),
                SmartHomeTarget(DeviceId("plug"), "Kettle plug"),
            ),
            command = SmartHomeCommand.ON,
        )

        val decoded = RuleCodec
            .decode(RuleCodec.encode(listOf(rule(action = action))), withSmartHome)!!
            .single()

        assertEquals(action, decoded.action)
    }

    @Test
    fun `a device name containing the separators does not corrupt the record`() {
        // Users name devices whatever they like, and "Lamp = kitchen; hall | main" is a
        // legal name. Encoding has to survive every separator the format uses.
        val withSmartHome = ActionCatalogue.WithSmartHome
        val action = SmartHomeDeviceAction(
            targets = listOf(SmartHomeTarget(DeviceId("a|b;c=d"), "Lamp = kitchen; hall | main")),
        )

        val decoded = RuleCodec
            .decode(RuleCodec.encode(listOf(rule(action = action))), withSmartHome)!!
            .single()

        assertEquals(action, decoded.action)
    }

    @Test
    fun `rules written by version 1 are still readable`() {
        // An upgrade must not delete the automations a user already had.
        val v1 = "v1\nrule.1|Double+clap|audio.double_clap|debug.vibrate|true|0.0|1500"

        val decoded = RuleCodec.decode(v1, catalogue)!!.single()

        assertEquals("rule.1", decoded.id)
        assertEquals("Double clap", decoded.name)
        assertEquals(TriggerId.DoubleClap, decoded.triggerId)
        assertEquals(VibrateAction(), decoded.action)
        assertEquals(1_500L, decoded.cooldownMillis)
    }

    @Test
    fun `a version 1 record with the field count of version 2 is rejected as malformed`() {
        // Field counts are per version, so a line cannot be silently misread as another
        // version's layout with everything shifted by one.
        val mixed = "v1\nrule.1|name|audio.double_clap|debug.vibrate|true|0.0|1500|extra"

        assertTrue(RuleCodec.decode(mixed, catalogue)!!.isEmpty())
    }

    @Test
    fun `encoding is stable, so saving an unchanged rule produces an unchanged blob`() {
        // DataStore wakes every collector on a write. An unstable encoding would restart
        // detection on an idle save.
        val rules = listOf(
            rule(
                action = SmartHomeDeviceAction(
                    targets = listOf(
                        SmartHomeTarget(DeviceId("b"), "Second"),
                        SmartHomeTarget(DeviceId("a"), "First"),
                    ),
                ),
            ),
        )

        assertEquals(RuleCodec.encode(rules), RuleCodec.encode(rules))
    }
}
