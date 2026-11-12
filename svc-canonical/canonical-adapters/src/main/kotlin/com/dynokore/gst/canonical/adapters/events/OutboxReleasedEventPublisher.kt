package com.dynokore.gst.canonical.adapters.events

import com.dynokore.gst.canonical.application.document.ReleasedEvent
import com.dynokore.gst.canonical.application.document.ReleasedEventPublisher
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import java.util.Locale

/**
 * Stages DocumentReleased through the transactional outbox in the caller's transaction (INV-REQ-479). The payload
 * `docsequence` is the number `appendSequenced` reserves for the same row; this class reads no counter.
 */
class OutboxReleasedEventPublisher(
    private val outbox: OutboxWriter,
    private val tenants: TenantContext,
    private val correlation: () -> CorrelationId,
    private val causation: () -> CausationId,
) : ReleasedEventPublisher {
    override fun stage(event: ReleasedEvent) {
        outbox.appendSequenced(
            TYPE,
            EventContext(
                tenant = tenants.current(),
                entity = EntityRef(event.entityRef),
                docUuid = DocUuid(event.docUuid),
                transactionDate = null,
                aggregateKey = event.docUuid.toString(),
                aggregateVersion = null,
                correlation = correlation(),
                causation = causation(),
            ),
        ) { docSequence -> payload(event, docSequence) }
    }

    private fun payload(
        event: ReleasedEvent,
        docSequence: Long,
    ): String =
        "{\"docUuid\":${json(event.docUuid.toString())},\"docsequence\":$docSequence," +
            "\"releasedHoldReasons\":${event.releasedHoldReasons.joinToString(",", "[", "]") { json(it) }}}"

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
        const val TYPE = "com.dynokore.gst.canonical.DocumentReleased.v1"
    }
}
