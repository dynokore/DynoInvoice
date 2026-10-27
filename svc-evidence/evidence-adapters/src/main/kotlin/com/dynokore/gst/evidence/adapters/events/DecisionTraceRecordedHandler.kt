package com.dynokore.gst.evidence.adapters.events

import com.dynokore.gst.evidence.application.chain.RecordAppender
import com.dynokore.gst.evidence.domain.chain.RecordKind
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.util.UUID

/**
 * The consumer of `gst.canonical.decision-trace-recorded.v1` (INV-REQ-3650 b1, INV-Q-7213 A (2) and (3)): appends the
 * event's `decisionTrace` object to the chain of the locator of (docUuid, version), or keeps it pending until the
 * locator arrives. It runs in the inbox consumer's transaction, so the record, its `applied_record` row (or its
 * pending row), the inbox row and the offset commit together. A record topic has no `docsequence` check.
 *
 * A payload that lacks `docUuid`, `version` or the `decisionTrace` object is refused with [ContractViolation]: a poison
 * record that goes to the dead letter.
 */
class DecisionTraceRecordedHandler(
    private val appender: RecordAppender,
    private val clock: Clock = Clock.systemUTC(),
) : EventHandler {
    private val json = JsonMapper.builder().build()

    override fun handle(event: Envelope): HandleOutcome {
        val data = parse(event.dataJson)
        val trace = data.get("decisionTrace")
        if (trace == null || !trace.isObject) throw ContractViolation("the payload has no decisionTrace object")
        appender.appendBound(
            tenant = event.tenant,
            kind = RecordKind.DECISION_TRACE,
            docUuid = uuid(data),
            version = version(data),
            eventId = event.id.value,
            content = CanonicalJson.bytes(trace),
            arrivedAt = clock.instant(),
        )
        return HandleOutcome.APPLIED
    }

    private fun parse(text: String): JsonNode =
        try {
            json.readTree(text)
        } catch (e: tools.jackson.core.JacksonException) {
            throw ContractViolation("the payload is not JSON", e)
        }

    private fun uuid(data: JsonNode): UUID =
        runCatching { UUID.fromString(data.get("docUuid")?.takeIf { it.isString }?.asString()) }
            .getOrElse { throw ContractViolation("docUuid is missing or not a UUID", it) }

    private fun version(data: JsonNode): Int =
        data.get("version")?.takeIf { it.isIntegralNumber && it.canConvertToInt() && it.asInt() >= 1 }?.asInt()
            ?: throw ContractViolation("version is missing or not a positive integer")
}
