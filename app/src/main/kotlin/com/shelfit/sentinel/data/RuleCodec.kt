package com.shelfit.sentinel.data

import com.shelfit.sentinel.core.action.ActionCatalogue
import com.shelfit.sentinel.core.rule.AutomationRule
import com.shelfit.sentinel.core.trigger.TriggerId
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Turns rules into a single string and back.
 *
 * A hand-written codec rather than a serialisation library: there are seven fields, the
 * dependency list is deliberately short, and the format needs to stay readable by older
 * builds. One line per rule, fields separated by `|`, text fields percent-encoded so no
 * value can contain the separator.
 *
 * The blob always opens with a version line, which means an empty rule list is still a
 * non-empty string — that is what distinguishes "the user deleted every rule" from "this
 * device has never been configured", and stops defaults being re-seeded over a
 * deliberate choice.
 *
 * Decoding is forgiving on purpose. A malformed line is skipped rather than failing the
 * whole read, and a rule naming an action or trigger this build does not know is kept
 * with that part unresolved. Losing a user's rules because a downgrade did not recognise
 * one field would be far worse than showing it as unavailable.
 */
internal object RuleCodec {

    private const val VERSION = "v1"
    private const val SEPARATOR = '|'
    private const val FIELD_COUNT = 7
    private const val CHARSET = "UTF-8"

    fun encode(rules: List<AutomationRule>): String =
        (listOf(VERSION) + rules.map(::encodeRule)).joinToString("\n")

    /** @return null when [blob] is absent, meaning this device has never been configured. */
    fun decode(blob: String?, catalogue: ActionCatalogue): List<AutomationRule>? {
        if (blob.isNullOrEmpty()) return null
        val lines = blob.lineSequence().toList()
        if (lines.firstOrNull() != VERSION) return null
        return lines.drop(1).mapNotNull { decodeRule(it, catalogue) }
    }

    private fun encodeRule(rule: AutomationRule): String = listOf(
        escape(rule.id),
        escape(rule.name),
        escape(rule.triggerId.value),
        escape(rule.action?.type ?: ""),
        rule.enabled.toString(),
        rule.minimumConfidence.toString(),
        rule.cooldownMillis.toString(),
    ).joinToString(SEPARATOR.toString())

    private fun decodeRule(line: String, catalogue: ActionCatalogue): AutomationRule? {
        if (line.isBlank()) return null
        val fields = line.split(SEPARATOR)
        if (fields.size != FIELD_COUNT) return null

        val id = unescape(fields[0]).ifBlank { return null }
        val triggerId = unescape(fields[2]).ifBlank { return null }

        return AutomationRule(
            id = id,
            name = unescape(fields[1]),
            triggerId = TriggerId(triggerId),
            // An unknown action type leaves the rule actionless rather than dropping it.
            action = catalogue.action(unescape(fields[3]).ifBlank { null }),
            enabled = fields[4].toBooleanStrictOrNull() ?: return null,
            minimumConfidence = fields[5].toFloatOrNull() ?: return null,
            cooldownMillis = fields[6].toLongOrNull() ?: return null,
        )
    }

    private fun escape(value: String): String = URLEncoder.encode(value, CHARSET)

    private fun unescape(value: String): String =
        runCatching { URLDecoder.decode(value, CHARSET) }.getOrDefault("")
}
