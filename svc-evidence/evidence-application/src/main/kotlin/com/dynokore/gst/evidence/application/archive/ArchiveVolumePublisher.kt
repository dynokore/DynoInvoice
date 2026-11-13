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

/**
 * Publishes the measured archive volume of an entity (INV-Q-12683 A, INV-Q-9829 A): in the caller's transaction it
 * records (tenant, entity, bytes, measuredAt) in the ledger and appends one `ArchiveVolumeMeasured` event to the
 * outbox, both with the same `measuredAt`. The trace ids come from the caller
 * (INV-Q-12736 A) and are passed on unchanged. An unchanged value still appends an event (at-least-once). It catches
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
        correlation: CorrelationId,
        causation: CausationId,
    ) {
        check(bytes >= 0) { "the archive volume is negative" }
        val measuredAt = clock.instant()
        ledger.record(tenant, entityRef, bytes, measuredAt)
        val at = DateTimeFormatter.ISO_INSTANT.format(measuredAt)
        val entity = EntityRef(entityRef)
        // The tenant-wide entity is a platform event (INV-Q-14069 A): no entityRef on the wire.
        val tenantWide = entity.isTenantWide
        val payload =
            if (tenantWide) {
                "{\"tenantRef\":${json(tenant.value.toString())},\"bytes\":$bytes,\"measuredAt\":${json(at)}}"
            } else {
                "{\"tenantRef\":${json(tenant.value.toString())},\"entityRef\":${json(entityRef)}," +
                    "\"bytes\":$bytes,\"measuredAt\":${json(at)}}"
            }
        outbox.append(
            listOf(OutboundEvent(if (tenantWide) TENANT_WIDE_EVENT_TYPE else EVENT_TYPE, payload)),
            EventContext(tenant, entity, null, null, entityRef, null, correlation, causation),
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
        const val TENANT_WIDE_EVENT_TYPE = "com.dynokore.gst.platform.TenantWideArchiveVolumeMeasured.v1"
    }
}
