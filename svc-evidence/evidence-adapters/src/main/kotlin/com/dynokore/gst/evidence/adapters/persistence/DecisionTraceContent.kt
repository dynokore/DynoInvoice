package com.dynokore.gst.evidence.adapters.persistence

import com.dynokore.gst.evidence.application.lookup.DecisionEntry
import com.dynokore.gst.evidence.application.lookup.PackVersionRef
import com.dynokore.gst.evidence.application.lookup.StoredDecisionTrace
import com.dynokore.gst.evidence.application.lookup.TraceInput
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

/**
 * Parses the stored `decisionTrace` object into its `decisions` (INV-Q-20711 A, tolerant reader): only `decisions` is
 * read, every other member of the object and every unknown member of an entry is ignored, and a member that is
 * missing or breaks the contract shape makes the whole content [StoredDecisionTrace.Malformed], never a partial list.
 */
object DecisionTraceContent {
    private val mapper = JsonMapper.builder().build()
    private val CODE = Regex("^[A-Z]{3}-[0-9]{3}$")
    private val TERM = Regex("^IBT-[0-9]{3}$")
    private const val RULE_MAX = 80
    private const val PACK_MAX = 100
    private const val VERSION_MAX = 40
    private const val NAME_MAX = 100
    private const val VALUE_MAX = 500
    private const val ASSERTION_TEXT_MAX = 500

    fun parse(
        recordedAt: Instant,
        content: ByteArray,
    ): StoredDecisionTrace {
        val decisions =
            try {
                decisionsOf(mapper.readTree(content))
            } catch (_: JacksonException) {
                null
            }
        return if (decisions == null) {
            StoredDecisionTrace.Malformed
        } else {
            StoredDecisionTrace.Valid(recordedAt, decisions)
        }
    }

    private fun decisionsOf(root: JsonNode): List<DecisionEntry>? {
        val list = if (root.isObject) root.get("decisions") else null
        if (list == null || !list.isArray || list.isEmpty) return null
        val entries = list.toList().map { entry(it) }
        return if (entries.any { it == null }) null else entries.filterNotNull()
    }

    private fun entry(n: JsonNode): DecisionEntry? {
        val code = text(n, "code", 1, Int.MAX_VALUE)?.takeIf { CODE.matches(it) }
        val rule = text(n, "ruleId", 1, RULE_MAX)
        val packs = list(n, "packVersions", 1)?.map { pack(it) }
        val inputs = list(n, "inputs", 0)?.map { input(it) }
        val assertionNode = if (n.isObject) n.get("assertionText") else null
        val assertionText = if (assertionNode == null) null else text(n, "assertionText", 1, ASSERTION_TEXT_MAX)
        val whole = code != null && rule != null && packs != null && inputs != null
        val textOk = assertionNode == null || assertionText != null
        val partsOk = whole && textOk
        return if (partsOk && packs.none { it == null } && inputs.none { it == null }) {
            DecisionEntry(code, rule, packs.filterNotNull(), inputs.filterNotNull(), assertionText)
        } else {
            null
        }
    }

    /** The array member [key] of [n] with at least [min] items, or null. */
    private fun list(
        n: JsonNode,
        key: String,
        min: Int,
    ): List<JsonNode>? {
        val v = if (n.isObject) n.get(key) else null
        return if (v != null && v.isArray && v.size() >= min) v.toList() else null
    }

    private fun pack(n: JsonNode): PackVersionRef? {
        val id = text(n, "packIdentifier", 1, PACK_MAX)
        val v = text(n, "version", 1, VERSION_MAX)
        return if (id != null && v != null) PackVersionRef(id, v) else null
    }

    private fun input(n: JsonNode): TraceInput? {
        val name = text(n, "name", 1, NAME_MAX)
        val value = text(n, "value", 0, VALUE_MAX)
        val termNode = n.get("businessTermId")
        val term = if (termNode == null) null else text(n, "businessTermId", 1, Int.MAX_VALUE)
        val termOk = termNode == null || (term != null && TERM.matches(term))
        return if (name != null && value != null && termOk) TraceInput(name, value, term) else null
    }

    /** The string member [key] of [n] with a length in [min]..[max], or null when absent or not a string. */
    private fun text(
        n: JsonNode,
        key: String,
        min: Int,
        max: Int,
    ): String? {
        val v = if (n.isObject) n.get(key) else null
        return if (v != null && v.isString && v.asString().length in min..max) v.asString() else null
    }
}
