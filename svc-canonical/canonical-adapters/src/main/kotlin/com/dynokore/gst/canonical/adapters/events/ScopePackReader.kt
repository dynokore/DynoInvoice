package com.dynokore.gst.canonical.adapters.events

import com.dynokore.gst.canonical.adapters.persistence.ScopePackActivation
import com.dynokore.gst.canonical.adapters.persistence.ScopePackRow
import com.dynokore.gst.canonical.adapters.persistence.StagedScopePack
import com.dynokore.gst.canonical.domain.derivation.UpstreamFunction
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.tenancykernel.TenantId
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Reads `RulePackStaged` and `RulePackActivated` (INV-REQ-772). Both events are written under the platform tenant:
 * any other envelope tenant is a [ContractViolation] (dead letter). A staged pack of a type other than the exact
 * string `SCOPE` (the constant tenancy-svc's `RulePackStagedHandler` compares with; the contract text says `scope`
 * in lower case, the rules-svc and tenancy-svc code and every example use `SCOPE`) is no scope pack: null.
 *
 * For a scope pack the rows of `artefact.contents.classificationTable` that carry `documentTypeCode` (a string that
 * matches [TYPE_CODE], the pattern of scope-pack-contents.schema.json; absent exactly when `documentFunction` is
 * NOT_A_BILL) become the distinct pairs of type code and function. Anything else malformed fails closed. The code
 * values are not repeated here: the pattern is the contract's, the check constraint of the table is the store's.
 */
class ScopePackReader {
    private val json = JsonMapper.builder().build()

    /** The staged scope pack, or null when `packType` is not `SCOPE`. */
    fun readStaged(
        dataJson: String,
        tenant: TenantId,
    ): StagedScopePack? {
        requirePlatform(tenant)
        val data = parse(dataJson)
        if (text(data, PACK_TYPE) != SCOPE) return null
        val table =
            data
                .get(ARTEFACT)
                ?.get(CONTENTS)
                ?.get(CLASSIFICATION_TABLE)
                ?.takeIf { it.isArray && it.size() > 0 }
                ?: throw ContractViolation("$CLASSIFICATION_TABLE is missing or empty")
        return StagedScopePack(text(data, PACK_IDENTIFIER), text(data, VERSION), rows(table))
    }

    fun readActivation(
        dataJson: String,
        tenant: TenantId,
    ): ScopePackActivation {
        requirePlatform(tenant)
        val data = parse(dataJson)
        val effective =
            try {
                LocalDate.parse(text(data, EFFECTIVE_FROM))
            } catch (e: DateTimeParseException) {
                throw ContractViolation("$EFFECTIVE_FROM is not a date", e)
            }
        return ScopePackActivation(text(data, PACK_IDENTIFIER), text(data, VERSION), effective)
    }

    /** The distinct coded pairs of [table], in the order of first appearance. */
    private fun rows(table: JsonNode): Set<ScopePackRow> {
        val rows = linkedSetOf<ScopePackRow>()
        for (i in 0 until table.size()) {
            val node = table.get(i)
            if (!node.isObject) throw ContractViolation("a classification row is not an object")
            val function = function(node)
            val code = node.get(DOCUMENT_TYPE_CODE)
            if ((function == UpstreamFunction.NOT_A_BILL) != (code == null)) {
                throw ContractViolation("$DOCUMENT_TYPE_CODE is present exactly when the function is not NOT_A_BILL")
            }
            if (code != null) rows += ScopePackRow(typeCode(code), function)
        }
        return rows
    }

    private fun function(node: JsonNode): UpstreamFunction =
        UpstreamFunction.entries.firstOrNull { it.name == text(node, DOCUMENT_FUNCTION) }
            ?: throw ContractViolation("$DOCUMENT_FUNCTION is not one of the three functions")

    private fun typeCode(node: JsonNode): Int {
        val text = node.takeIf { it.isString }?.asString()
        if (text == null || !TYPE_CODE.matches(text)) {
            throw ContractViolation("$DOCUMENT_TYPE_CODE does not match the contract pattern ${TYPE_CODE.pattern}")
        }
        return text.toInt()
    }

    private fun requirePlatform(tenant: TenantId) {
        if (!tenant.isPlatform) throw ContractViolation("a rule pack event is written under the platform tenant")
    }

    private fun text(
        node: JsonNode,
        member: String,
    ): String =
        node.get(member)?.takeIf { it.isString && it.asString().isNotBlank() }?.asString()
            ?: throw ContractViolation("$member is missing or not a non-blank text")

    private fun parse(text: String): JsonNode =
        try {
            json.readTree(text).also { if (!it.isObject) throw ContractViolation("the payload is not a JSON object") }
        } catch (e: JacksonException) {
            throw ContractViolation("the payload is not JSON", e)
        }

    companion object {
        /** The exact pack type string, as in tenancy-svc `RulePackStagedHandler` (private const SCOPE). */
        const val SCOPE = "SCOPE"

        /** The `pattern` of `documentTypeCode` in the scope pack contents schema, copied verbatim. */
        val TYPE_CODE = Regex("^38[01]$")
        private const val PACK_TYPE = "packType"
        private const val PACK_IDENTIFIER = "packIdentifier"
        private const val VERSION = "version"
        private const val EFFECTIVE_FROM = "effectiveFrom"
        private const val ARTEFACT = "artefact"
        private const val CONTENTS = "contents"
        private const val CLASSIFICATION_TABLE = "classificationTable"
        private const val DOCUMENT_FUNCTION = "documentFunction"
        private const val DOCUMENT_TYPE_CODE = "documentTypeCode"
    }
}
