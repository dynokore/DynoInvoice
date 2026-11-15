package com.dynokore.gst.canonical.adapters.events

import com.dynokore.gst.canonical.adapters.persistence.PeriodSpanSet
import com.dynokore.gst.canonical.domain.dating.PeriodSpan
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.tenancykernel.TenantId
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Reads a `PeriodRecordsChanged` event into the complete [PeriodSpanSet] of an entity (INV-AC-358, ruling INV-Q-21406
 * A: event-carried state transfer). The tenant comes only from the envelope `tenantid` (CloudEvents attribute), never
 * from the data; the ordering version is the payload member `version`. The member `filingDueDate` is not read; the optional `periodId` of a period is kept (INV-AC-21535).
 *
 * Fails closed with [ContractViolation] (dead letter): a missing or blank `entityRef`, a missing, non-integer or
 * below-1 `version`, a missing or empty `periods`, a period without `periodRef`, `periodStart` or `periodEnd`, a date
 * that does not parse, a period whose end is before its start, or an envelope without a tenant (the platform tenant
 * stands for a missing `tenantid`).
 */
class PeriodRecordsReader {
    private val json = JsonMapper.builder().build()

    fun read(
        dataJson: String,
        tenant: TenantId,
    ): PeriodSpanSet {
        if (tenant.isPlatform) throw ContractViolation("the envelope carries no tenantid")
        val data = parse(dataJson)
        val periods =
            data.get(PERIODS)?.takeIf { it.isArray && it.size() > 0 }
                ?: throw ContractViolation("$PERIODS is missing or empty")
        val nodes = (0 until periods.size()).map { periods.get(it) }
        val spans = nodes.map { span(it) }
        val ids = linkedMapOf<String, UUID>()
        nodes.forEachIndexed { i, node -> periodId(node)?.let { ids[spans[i].periodRef] = it } }
        return PeriodSpanSet(tenant.value, entityRef(data), version(data), spans, ids)
    }

    private fun span(node: JsonNode): PeriodSpan {
        if (!node.isObject) throw ContractViolation("a period is not an object")
        val start = date(node, PERIOD_START)
        val end = date(node, PERIOD_END)
        if (end.isBefore(start)) throw ContractViolation("period end $end is before start $start")
        return PeriodSpan(text(node, PERIOD_REF), start, end)
    }

    /** The optional `periodId`: absent (or JSON null) is null; a value that is not a canonical lower-case uuid fails. */
    private fun periodId(node: JsonNode): UUID? {
        val member = node.get(PERIOD_ID)?.takeUnless { it.isNull } ?: return null
        val text = member.takeIf { it.isString }?.asString()
        val id = text?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (id == null || id.toString() != text) throw ContractViolation("$PERIOD_ID is not a canonical uuid")
        return id
    }

    private fun date(
        node: JsonNode,
        member: String,
    ): LocalDate =
        try {
            LocalDate.parse(text(node, member))
        } catch (e: DateTimeParseException) {
            throw ContractViolation("$member is not a date", e)
        }

    private fun text(
        node: JsonNode,
        member: String,
    ): String =
        node.get(member)?.takeIf { it.isString && it.asString().isNotBlank() }?.asString()
            ?: throw ContractViolation("$member is missing or not a non-blank text")

    private fun entityRef(data: JsonNode): String = text(data, ENTITY_REF)

    private fun version(data: JsonNode): Long =
        data
            .get(VERSION)
            ?.takeIf { it.isIntegralNumber && it.canConvertToLong() }
            ?.asLong()
            ?.takeIf { it >= 1 }
            ?: throw ContractViolation("$VERSION is missing, not an integer or below 1")

    private fun parse(text: String): JsonNode =
        try {
            json.readTree(text).also { if (!it.isObject) throw ContractViolation("the payload is not a JSON object") }
        } catch (e: JacksonException) {
            throw ContractViolation("the payload is not JSON", e)
        }

    private companion object {
        const val ENTITY_REF = "entityRef"
        const val VERSION = "version"
        const val PERIODS = "periods"
        const val PERIOD_REF = "periodRef"
        const val PERIOD_START = "periodStart"
        const val PERIOD_END = "periodEnd"
        const val PERIOD_ID = "periodId"
    }
}
