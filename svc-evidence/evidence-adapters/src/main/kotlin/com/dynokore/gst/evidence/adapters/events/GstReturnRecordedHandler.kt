package com.dynokore.gst.evidence.adapters.events

import com.dynokore.gst.evidence.application.digest.DigestObjectConflict
import com.dynokore.gst.evidence.application.digest.FiledReturn
import com.dynokore.gst.evidence.application.digest.PeriodDigestRecorder
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

/**
 * The consumer of `gst.reconciliation.gst-return-recorded.v1` (INV-Q-7251 A (1)): the filing of a return closes its
 * period, so the event writes the period digest of the entity's three document chains. It runs in the inbox consumer's
 * transaction, so the digest rows, the inbox row and the offset commit together; the dedupe on the event id is the
 * inbox's.
 *
 * `returnVersion` is read as the contract types it: a JSON string of ASCII decimal digits that starts with 1 to 9 (no
 * sign, no leading zero, no spaces, no fraction) and fits a positive 32-bit integer; it becomes the integer of the
 * digest entry, so the digest text is unchanged. Any other type or text is a contract violation (fail closed).
 *
 * A payload without a usable `entityRef`, `period`, `returnVersion` or `filedAt`, and an object store key that holds
 * other bytes than the digest, are refused with [ContractViolation]: poison records that go to the dead letter.
 */
class GstReturnRecordedHandler(
    private val recorder: PeriodDigestRecorder,
) : EventHandler {
    private val json = JsonMapper.builder().build()

    override fun handle(event: Envelope): HandleOutcome {
        val data =
            try {
                json.readTree(event.dataJson)
            } catch (e: tools.jackson.core.JacksonException) {
                throw ContractViolation("the payload is not JSON", e)
            }
        val filed =
            FiledReturn(
                eventId = event.id.value,
                entityRef = text(data, "entityRef"),
                period = text(data, "period"),
                returnVersion = returnVersion(data),
                filedAt = filedAt(data),
            )
        try {
            recorder.record(event.tenant, filed)
        } catch (e: DigestObjectConflict) {
            throw ContractViolation("the object ${e.key} holds other bytes than the digest", e)
        }
        return HandleOutcome.APPLIED
    }

    private fun text(
        data: JsonNode,
        name: String,
    ): String =
        data.get(name)?.takeIf { it.isString && it.asString().isNotBlank() }?.asString()
            ?: throw ContractViolation("$name is missing or not a text")

    private fun returnVersion(data: JsonNode): Int =
        data
            .get("returnVersion")
            ?.takeIf { it.isString }
            ?.asString()
            ?.takeIf { it.length <= MAX_VERSION_DIGITS && it.matches(VERSION_DIGITS) }
            ?.toIntOrNull()
            ?: throw ContractViolation(
                "returnVersion is not a string of decimal digits for a positive integer " +
                    "([1-9][0-9]*, at most 2147483647)",
            )

    private fun filedAt(data: JsonNode): Instant =
        runCatching { Instant.parse(data.get("filedAt")?.takeIf { it.isString }?.asString()) }
            .getOrElse { throw ContractViolation("filedAt is missing or not a timestamp", it) }

    private companion object {
        val VERSION_DIGITS = Regex("[1-9][0-9]*")
        const val MAX_VERSION_DIGITS = 10
    }
}
