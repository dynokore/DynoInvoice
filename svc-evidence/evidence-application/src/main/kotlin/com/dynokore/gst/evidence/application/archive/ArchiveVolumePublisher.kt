package com.dynokore.gst.evidence.application.archive

import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Clock
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * Publishes the measured archive volume of an entity (INV-Q-12683 A, INV-Q-9829 A): in the caller's transaction it
 * records (tenant, entity, bytes, measuredAt) in the ledger and appends one `ArchiveVolumeMeasured` event to the
 * outbox, both with the same `measuredAt`. An unchanged value still appends an event (at-least-once). It catches
 * nothing: a failed append reaches the caller, whose transaction rolls the ledger row back.
 */
class ArchiveVolumePublisher(
    private val ledger: ArchiveVolumeLedger,
    private val outbox: OutboxWriter,
    private val clock: Clock,
) {
    fun publish(
        tenant: TenantId,
        entityRef: String,
        bytes: Long,
    ) {
        check(bytes >= 0) { "the archive volume is negative" }
        val measuredAt = clock.instant()
        ledger.record(tenant, entityRef, bytes, measuredAt)
        val at = DateTimeFormatter.ISO_INSTANT.format(measuredAt)
        val payload =
            "{\"tenantRef\":${json(tenant.value.toString())},\"entityRef\":${json(entityRef)}," +
                "\"bytes\":$bytes,\"measuredAt\":${json(at)}}"
        val id = UUID.randomUUID().toString()
        outbox.append(
            listOf(OutboundEvent(EVENT_TYPE, payload)),
            EventContext(tenant, EntityRef(entityRef), null, null, entityRef, null, CorrelationId(id), CausationId(id)),
        )
    }

    private fun json(text: String): String {
        val out = StringBuilder("\"")
        for (c in text) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c < ' ' -> out.append("\\u%04x".format(Locale.ROOT, c.code))
                else -> out.append(c)
            }
        }
        return out.append('"').toString()
    }

    companion object {
        const val EVENT_TYPE = "com.dynokore.gst.evidence.ArchiveVolumeMeasured.v1"
    }
}
