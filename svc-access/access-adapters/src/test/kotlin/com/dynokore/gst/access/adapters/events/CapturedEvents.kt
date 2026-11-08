package com.dynokore.gst.access.adapters.events

import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EnvelopeJson
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import java.time.Instant
import java.util.UUID

/**
 * A fake [OutboxWriter] that keeps each appended event with the [EventContext] it was appended under, and builds the
 * real [Envelope] the way the JDBC writer does, so the library's own envelope rules apply to what the tests append.
 */
class CapturedEvents : OutboxWriter {
    class Appended(
        val event: OutboundEvent,
        val context: EventContext,
    ) {
        val envelope: Envelope =
            Envelope(
                id = EventId(UUID.randomUUID().toString()),
                source = "/gst/access-svc",
                type = event.type,
                subject = context.aggregateKey,
                time = Instant.parse("2026-11-08T00:00:00Z"),
                tenant = context.tenant,
                entity = context.entity,
                docUuid = context.docUuid,
                transactionDate = context.transactionDate,
                docSequence = null,
                instanceId = "test",
                correlation = context.correlation,
                causation = context.causation,
                dataJson = event.dataJson,
            )

        /** The envelope after its structured-JSON form, as the event log delivers it to a consumer. */
        fun delivered(): Envelope = EnvelopeJson().read(EnvelopeJson().write(envelope))
    }

    val appended = mutableListOf<Appended>()

    override fun append(
        events: List<OutboundEvent>,
        context: EventContext,
    ): List<Envelope> {
        val made = events.map { Appended(it, context) }
        appended += made
        return made.map { it.envelope }
    }
}
