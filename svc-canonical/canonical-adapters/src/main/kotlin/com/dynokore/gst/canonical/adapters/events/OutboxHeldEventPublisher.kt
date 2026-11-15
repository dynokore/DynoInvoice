package com.dynokore.gst.canonical.adapters.events

import com.dynokore.gst.canonical.application.document.HeldEvent
import com.dynokore.gst.canonical.application.document.HeldEventPublisher
import com.dynokore.gst.canonical.application.document.PARTY_REF_MAX_LENGTH
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Stages DocumentHeld through the transactional outbox in the caller's transaction (INV-REQ-479, INV-REQ-1221). The
 * payload `docsequence` is the number `appendSequenced` reserves for the same row; this class reads no counter.
 * Absent optional members are omitted, never written as null. `code`, `remediationOwner` and `disposition` are not set.
 */
class OutboxHeldEventPublisher(
    private val outbox: OutboxWriter,
    private val tenants: TenantContext,
    private val correlation: () -> CorrelationId,
    private val causation: () -> CausationId,
) : HeldEventPublisher {
    override fun stage(event: HeldEvent) {
        event.partyRef?.let {
            require(it.isNotBlank()) { "partyRef must not be blank" }
            require(it.length <= PARTY_REF_MAX_LENGTH) { "partyRef is longer than $PARTY_REF_MAX_LENGTH characters" }
        }
        val key = event.docUuid.toString()
        outbox.appendSequenced(
            TYPE,
            EventContext(
                tenant = tenants.current(),
                entity = EntityRef(event.entityRef),
                docUuid = DocUuid(event.docUuid),
                transactionDate = null,
                aggregateKey = key,
                aggregateVersion = null,
                correlation = correlation(),
                causation = causation(),
            ),
        ) { docSequence -> payload(event, docSequence) }
    }

    private fun payload(
        event: HeldEvent,
        docSequence: Long,
    ): String {
        val members = linkedMapOf<String, String>()
        members["docUuid"] = json(event.docUuid.toString())
        members["docsequence"] = docSequence.toString()
        members["holdReasons"] = event.holdReasons.joinToString(",", "[", "]") { json(it) }
        members["entityRef"] = json(event.entityRef)
        members["direction"] = json(event.direction.name)
        members["documentDate"] = json(event.documentDate.toString())
        members["prescribedPeriodRef"] = json(event.prescribedPeriodRef)
        event.postingTimestamp?.let {
            members["postingTimestamp"] = json(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(it))
        }
        event.invoiceIssuedOrReceived?.let { members["invoiceIssuedOrReceived"] = it.toString() }
        event.partyRef?.let { members["partyRef"] = json(it) }
        return members.entries.joinToString(",", "{", "}") { (name, value) -> "${json(name)}:$value" }
    }

    private fun json(text: String): String =
        buildString {
            append('"')
            text.forEach { c ->
                when {
                    c == '"' -> append("\\\"")
                    c == '\\' -> append("\\\\")
                    c < ' ' -> append(String.format(Locale.ROOT, "\\u%04x", c.code))
                    else -> append(c)
                }
            }
            append('"')
        }

    private companion object {
        const val TYPE = "com.dynokore.gst.canonical.DocumentHeld.v1"
    }
}
