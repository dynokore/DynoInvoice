package com.dynokore.gst.evidence.adapters.events

import com.dynokore.gst.evidence.application.export.ExportResult
import com.dynokore.gst.evidence.application.export.ExportResultPublisher
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantId
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.util.UUID

/**
 * [ExportResultPublisher] over the transactional outbox (INV-REQ-877): appends one `OffboardingExportFinished` event
 * in the caller's transaction, so it commits or rolls back with the job row. The event is a tenant-wide one.
 */
class OffboardingExportFinishedOutbox(
    private val outbox: OutboxWriter,
    private val clock: Clock,
) : ExportResultPublisher {
    private val json = JsonMapper.builder().build()

    override fun publish(result: ExportResult) {
        val payload =
            json.createObjectNode().apply {
                put("tenantRef", result.tenantRef)
                put("offboardingRef", result.offboardingRef)
                put("exportRef", result.exportRef)
                put("state", result.state.name)
                putArray("contents").also { array -> result.contents.forEach { array.add(it) } }
                put("finishedAt", clock.instant().toString())
                result.failureReason?.let { put("failureReason", it) }
            }
        outbox.append(
            listOf(OutboundEvent(EVENT_TYPE, json.writeValueAsString(payload))),
            EventContext(
                TenantId(UUID.fromString(result.tenantRef)),
                EntityRef.TENANT_WIDE,
                null,
                null,
                result.exportRef,
                null,
                CorrelationId(result.offboardingRef),
                CausationId(result.exportRef),
            ),
        )
    }

    companion object {
        const val EVENT_TYPE = "com.dynokore.gst.evidence.OffboardingExportFinished.v1"
    }
}
