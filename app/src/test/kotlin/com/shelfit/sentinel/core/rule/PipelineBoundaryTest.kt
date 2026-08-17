package com.shelfit.sentinel.core.rule

import com.shelfit.sentinel.core.action.SmartHomeActionExecutor
import com.shelfit.sentinel.core.trigger.TriggerEvent
import com.shelfit.sentinel.trigger.audio.ClapCandidateDetector
import com.shelfit.sentinel.trigger.audio.ClapFeatureExtractor
import com.shelfit.sentinel.trigger.audio.DoubleClapConfiguration
import com.shelfit.sentinel.trigger.audio.DoubleClapDetector
import com.shelfit.sentinel.trigger.audio.DoubleClapStateMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the boundary this stage exists to protect: a detector must not know what
 * happens after it reports.
 *
 * Comments and good intentions do not survive refactoring; these assertions do. The
 * moment somebody passes an `ActionDispatcher` into a detector "just to make the
 * notification easier", this fails.
 */
class PipelineBoundaryTest {

    private val actionPackage = "com.shelfit.sentinel.core.action"

    @Test
    fun `a detector cannot be handed anything from the action layer`() {
        val offending = DoubleClapDetector::class.java.constructors
            .flatMap { it.parameterTypes.toList() }
            .filter { it.name.startsWith(actionPackage) }
            .map { it.name }

        assertEquals(
            "a detector given an action collaborator could perform actions itself",
            emptyList<String>(),
            offending,
        )
    }

    @Test
    fun `a detector exposes nothing from the action layer`() {
        val offending = DoubleClapDetector::class.java.declaredMethods
            .filter { method ->
                method.returnType.name.startsWith(actionPackage) ||
                    method.parameterTypes.any { it.name.startsWith(actionPackage) }
            }
            .map { it.name }

        assertEquals(emptyList<String>(), offending)
    }

    @Test
    fun `a detector holds no reference to the action layer`() {
        val offending = DoubleClapDetector::class.java.declaredFields
            .filterNot { it.isSynthetic }
            .filter { it.type.name.startsWith(actionPackage) }
            .map { it.name }

        assertEquals(emptyList<String>(), offending)
    }

    /**
     * The event is the whole contract between the two halves. If an [com.shelfit.sentinel.core.action.Action]
     * could travel inside it, a detector could choose what happens next — which is the
     * coupling the rule layer exists to prevent.
     */
    @Test
    fun `a trigger event cannot carry an action`() {
        val offending = TriggerEvent::class.java.declaredFields
            .filterNot { it.isSynthetic }
            .filter { it.type.name.startsWith(actionPackage) }
            .map { it.name }

        assertEquals(emptyList<String>(), offending)
    }

    @Test
    fun `a trigger event carries only identity, timing, confidence and small detail`() {
        val fields = TriggerEvent::class.java.declaredFields
            .filterNot { it.isSynthetic }
            .filter { !it.name.contains('$') }
            .map { it.name }
            .toSet()

        assertEquals(
            setOf("triggerId", "elapsedRealtimeMillis", "confidence", "detail"),
            fields,
        )
    }

    /**
     * States the asymmetry as an assertion: the rule references both halves, and neither
     * half references the rule.
     *
     * The trigger side is checked by field name rather than type, because `TriggerId` is
     * an inline value class and erases to `String` — reflection cannot see it.
     */
    @Test
    fun `the rule layer is what knows about both halves`() {
        val fields = AutomationRule::class.java.declaredFields.filterNot { it.isSynthetic }

        assertTrue(
            "a rule names an action",
            fields.any { it.type.name.startsWith(actionPackage) },
        )
        assertTrue(
            "a rule names a trigger",
            fields.any { it.name == "triggerId" },
        )
    }

    /**
     * The stage-6 requirement, as an assertion: the clap modules must not depend on a
     * smart-home provider.
     *
     * Checked against the whole audio package rather than the detector alone, because the
     * tempting shortcut is not in the detector — it is a helper somewhere in the pipeline
     * that "just needs to know whether the lights are on".
     */
    @Test
    fun `the audio pipeline knows nothing about smart homes`() {
        val smartHomePackage = "com.shelfit.sentinel.core.smarthome"

        val audioClasses = listOf(
            DoubleClapDetector::class.java,
            ClapFeatureExtractor::class.java,
            ClapCandidateDetector::class.java,
            DoubleClapStateMachine::class.java,
            DoubleClapConfiguration::class.java,
        )

        val offending = audioClasses.flatMap { type ->
            val referenced = type.declaredFields.filterNot { it.isSynthetic }.map { it.type.name } +
                type.declaredMethods.flatMap {
                    it.parameterTypes.map(Class<*>::getName) + it.returnType.name
                } +
                type.constructors.flatMap { it.parameterTypes.map(Class<*>::getName) }
            referenced.filter { it.startsWith(smartHomePackage) }.map { "${type.simpleName}: $it" }
        }

        assertEquals(
            "a detector that can read a light bulb can also decide what to do about it",
            emptyList<String>(),
            offending,
        )
    }

    /**
     * The smart-home executor is the meeting point, and it must meet the *interface*.
     *
     * If a vendor type reached it, the seam would have failed and the rule layer would be
     * coupled to one provider.
     */
    @Test
    fun `the smart-home executor holds only the app's own client interface`() {
        val platformPackage = "com.shelfit.sentinel.platform"

        val offending = SmartHomeActionExecutor::class.java
            .let { type ->
                type.declaredFields.filterNot { it.isSynthetic }.map { it.type.name } +
                    type.constructors.flatMap { it.parameterTypes.map(Class<*>::getName) }
            }
            .filter { it.startsWith(platformPackage) }

        assertEquals(emptyList<String>(), offending)
    }

    @Test
    fun `neither half references the rule layer`() {
        val rulePackage = "com.shelfit.sentinel.core.rule"

        val detectorTypes = DoubleClapDetector::class.java.let { type ->
            type.declaredFields.filterNot { it.isSynthetic }.map { it.type.name } +
                type.declaredMethods.map { it.returnType.name }
        }
        val eventTypes = TriggerEvent::class.java.declaredFields
            .filterNot { it.isSynthetic }
            .map { it.type.name }

        assertTrue(
            "a detector must not know about rules",
            detectorTypes.none { it.startsWith(rulePackage) },
        )
        assertTrue(
            "an event must not know about rules",
            eventTypes.none { it.startsWith(rulePackage) },
        )
    }
}
