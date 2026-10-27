package com.dynokore.gst.tenancy.adapters.events

import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.SHORT_CODE
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import org.jooq.DSLContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * The consumer of `gst.aggregation.short-code-used.v1` (INV-Q-11266 A, INV-AC-2779): sets `used_in_aggregate_number`
 * of the registered short code once, in the inbox consumer's transaction on the tenant-bound [DSLContext]. The flag is
 * never reset here. A code that no row has (changed in tenancy-svc before the event arrived) is logged and changes
 * nothing; the log line carries the tenant, the code, the node kind and the aggregate id only (no personal data).
 */
class ShortCodeUsedHandler(
    private val dsl: DSLContext,
) : EventHandler {
    private val json = JsonMapper.builder().build()

    override fun handle(event: Envelope): HandleOutcome {
        val data = parse(event.dataJson)
        val code = text(data, "code")
        val nodeKind = text(data, "nodeKind")
        text(data, "nodeRef")
        val aggregateId = text(data, "aggregateId")
        text(data, "documentNumber")
        if (!CODE.matches(code)) throw ContractViolation("code is not 1 to 10 of A-Z and 0-9")
        if (nodeKind !in NODE_KINDS) throw ContractViolation("nodeKind is neither ENTITY nor BUSINESS_UNIT")
        val changed =
            dsl
                .update(SHORT_CODE)
                .set(SHORT_CODE.USED_IN_AGGREGATE_NUMBER, true)
                .where(SHORT_CODE.TENANT_ID.eq(event.tenant.value))
                .and(SHORT_CODE.CODE.eq(code))
                .and(SHORT_CODE.NODE_KIND.eq(nodeKind))
                .and(SHORT_CODE.USED_IN_AGGREGATE_NUMBER.isFalse)
                .execute()
        if (changed == 1) return HandleOutcome.APPLIED
        val registered =
            dsl.fetchExists(
                SHORT_CODE,
                SHORT_CODE.TENANT_ID
                    .eq(
                        event.tenant.value,
                    ).and(SHORT_CODE.CODE.eq(code))
                    .and(SHORT_CODE.NODE_KIND.eq(nodeKind)),
            )
        if (!registered) {
            log.warn(
                "ShortCodeUsed for a code no row has: tenant={} code={} nodeKind={} aggregateId={}",
                event.tenant.value,
                code,
                nodeKind,
                aggregateId,
            )
        }
        return HandleOutcome.IGNORED
    }

    private fun parse(text: String): JsonNode =
        try {
            json.readTree(text)
        } catch (e: tools.jackson.core.JacksonException) {
            throw ContractViolation("the payload is not JSON", e)
        }

    private fun text(
        data: JsonNode,
        name: String,
    ): String {
        val node = data.get(name)?.takeUnless { it.isNull } ?: throw ContractViolation("the payload has no $name")
        val value = if (node.isString) node.asString() else null
        return value?.takeUnless { it.isBlank() } ?: throw ContractViolation("$name is not a non-blank string")
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(ShortCodeUsedHandler::class.java)
        val CODE = Regex("^[A-Z0-9]{1,10}$")
        val NODE_KINDS = setOf("ENTITY", "BUSINESS_UNIT")
    }
}
