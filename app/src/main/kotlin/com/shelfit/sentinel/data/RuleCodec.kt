package com.shelfit.sentinel.data

import com.shelfit.sentinel.core.action.ActionCatalogue
import com.shelfit.sentinel.core.rule.AutomationRule
import com.shelfit.sentinel.core.trigger.TriggerId
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Turns rules into a single string and back.
 *
 * A hand-written codec rather than a serialisation library: there are a handful of fields,
 * the dependency list is deliberately short, and the format needs to stay readable by older
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
 *
 * ### Versions
 *
 * - `v1` — seven fields, no action parameters. Still read, so an upgrade keeps existing
 *   rules; rewritten as `v2` the next time anything is saved.
 * - `v2` — adds an eighth field holding the action's parameters.
 *
 * Reading old versions is not optional politeness. A user who has spent time tuning rules
 * should never lose them to an app update.
 */
internal object RuleCodec {

    private const val VERSION_1 = "v1"
    private const val VERSION_2 = "v2"
    private const val CURRENT_VERSION = VERSION_2

    private const val SEPARATOR = '|'
    private const val PAIR_SEPARATOR = ';'
    private const val KEY_VALUE_SEPARATOR = '='
    private const val CHARSET = "UTF-8"

    /** Field counts per version, so a line from the wrong version is rejected, not misread. */
    private val fieldCounts = mapOf(VERSION_1 to 7, VERSION_2 to 8)

    fun encode(rules: List<AutomationRule>): String =
        (listOf(CURRENT_VERSION) + rules.map(::encodeRule)).joinToString("\n")

    /** @return null when [blob] is absent, meaning this device has never been configured. */
    fun decode(blob: String?, catalogue: ActionCatalogue): List<AutomationRule>? {
        if (blob.isNullOrEmpty()) return null
        val lines = blob.lineSequence().toList()
        val version = lines.firstOrNull() ?: return null
        val fieldCount = fieldCounts[version] ?: return null
        return lines.drop(1).mapNotNull { decodeRule(it, fieldCount, catalogue) }
    }

    private fun encodeRule(rule: AutomationRule): String = listOf(
        escape(rule.id),
        escape(rule.name),
        escape(rule.triggerId.value),
        escape(rule.action?.type ?: ""),
        rule.enabled.toString(),
        rule.minimumConfidence.toString(),
        rule.cooldownMillis.toString(),
        encodeParameters(rule.action?.parameters ?: emptyMap()),
    ).joinToString(SEPARATOR.toString())

    private fun decodeRule(
        line: String,
        fieldCount: Int,
        catalogue: ActionCatalogue,
    ): AutomationRule? {
        if (line.isBlank()) return null
        val fields = line.split(SEPARATOR)
        if (fields.size != fieldCount) return null

        val id = unescape(fields[0]).ifBlank { return null }
        val triggerId = unescape(fields[2]).ifBlank { return null }
        // v1 lines have no parameters field; the action rebuilds itself from its defaults.
        val parameters = fields.getOrNull(7)?.let(::decodeParameters) ?: emptyMap()

        return AutomationRule(
            id = id,
            name = unescape(fields[1]),
            triggerId = TriggerId(triggerId),
            // An unknown action type leaves the rule actionless rather than dropping it.
            action = catalogue.action(unescape(fields[3]).ifBlank { null }, parameters),
            enabled = fields[4].toBooleanStrictOrNull() ?: return null,
            minimumConfidence = fields[5].toFloatOrNull() ?: return null,
            cooldownMillis = fields[6].toLongOrNull() ?: return null,
        )
    }

    /**
     * `key=value;key=value`, keys and values percent-encoded.
     *
     * Encoding the parts rather than the whole field is what keeps this readable and avoids
     * a second escaping pass: percent-encoding removes `|`, `;` and `=` from every key and
     * value, so the three separators can never be ambiguous.
     *
     * Keys are sorted so the same action always encodes to the same string. Without that,
     * saving an unchanged rule could rewrite the blob and wake every DataStore collector.
     */
    private fun encodeParameters(parameters: Map<String, String>): String =
        parameters.entries
            .sortedBy { it.key }
            .joinToString(PAIR_SEPARATOR.toString()) { (key, value) ->
                "${escape(key)}$KEY_VALUE_SEPARATOR${escape(value)}"
            }

    private fun decodeParameters(field: String): Map<String, String> {
        if (field.isEmpty()) return emptyMap()
        return field.split(PAIR_SEPARATOR).mapNotNull { pair ->
            val index = pair.indexOf(KEY_VALUE_SEPARATOR)
            if (index <= 0) return@mapNotNull null
            val key = unescape(pair.substring(0, index)).ifBlank { return@mapNotNull null }
            key to unescape(pair.substring(index + 1))
        }.toMap()
    }

    private fun escape(value: String): String = URLEncoder.encode(value, CHARSET)

    private fun unescape(value: String): String =
        runCatching { URLDecoder.decode(value, CHARSET) }.getOrDefault("")
}
