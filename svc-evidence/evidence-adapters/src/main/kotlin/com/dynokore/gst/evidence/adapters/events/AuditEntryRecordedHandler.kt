package com.dynokore.gst.evidence.adapters.events

import com.dynokore.gst.evidence.application.chain.RecordAppender
import com.dynokore.gst.evidence.domain.chain.AuditChainKey
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock

/**
 * The consumer of `gst.platform.audit-entry-recorded.v1` (INV-REQ-3650 b1, INV-Q-7213 A (4)): appends the event's
 * `data` object to the `audit_log` chain given by [AuditChainKey] (the envelope's entity and the UTC month of its
 * `time`), with an empty index, and writes its `applied_record` row. Same transaction and no `docsequence` check as
 * the decision trace consumer.
 *
 * An event whose `data` lacks `actor`, `action` or `scope` is refused with [ContractViolation]: a poison record that
 * goes to the dead letter and stores nothing.
 */
class AuditEntryRecordedHandler(
    private val appender: RecordAppender,
    private val clock: Clock = Clock.systemUTC(),
) : EventHandler {
    private val json = JsonMapper.builder().build()

    override fun handle(event: Envelope): HandleOutcome {
        val data = parse(event.dataJson)
        REQUIRED.forEach { name ->
            if (data.get(name)?.isNull != false) throw ContractViolation("the audit entry has no $name")
        }
        appender.appendAudit(
            tenant = event.tenant,
            chainKey = AuditChainKey.of(event.entity.value, event.time),
            eventId = event.id.value,
            content = CanonicalJson.bytes(data),
            arrivedAt = clock.instant(),
        )
        return HandleOutcome.APPLIED
    }

    private fun parse(text: String): JsonNode =
        try {
            json.readTree(text).also { if (!it.isObject) throw ContractViolation("the payload is not a JSON object") }
        } catch (e: tools.jackson.core.JacksonException) {
            throw ContractViolation("the payload is not JSON", e)
        }

    private companion object {
        val REQUIRED = listOf("actor", "action", "scope")
    }
}
