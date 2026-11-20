package com.dynokore.gst.canonical.adapters.events

import com.dynokore.gst.canonical.application.sample.SamplePage
import com.dynokore.gst.canonical.application.sample.SamplePagePublisher
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * Stages one `PackDryRunSampleRecorded` page (platform event, INV-REQ-772 b1) through the transactional outbox in the
 * caller's transaction. The entity of the event is the `entityRef` of the request, the tenant is the bound one; a page
 * for another tenant than the bound one is refused. The payload is the page's members as the use case hands them over;
 * the event is not document-scoped, so it has no `docsequence`.
 */
class OutboxSamplePagePublisher(
    private val outbox: OutboxWriter,
    private val tenants: TenantContext,
    private val correlation: () -> CorrelationId,
    private val causation: () -> CausationId,
) : SamplePagePublisher {
    private val json = JsonMapper.builder().build()

    override fun publish(
        tenantId: UUID,
        entityRef: String,
        page: SamplePage,
    ) {
        val bound = tenants.current()
        require(bound.value == tenantId) { "the page is for a tenant other than the bound one" }
        outbox.append(
            listOf(OutboundEvent(TYPE, json.writeValueAsString(page.payload()))),
            EventContext(
                tenant = bound,
                entity = EntityRef(entityRef),
                docUuid = null,
                transactionDate = null,
                aggregateKey = page.dryRunId,
                aggregateVersion = null,
                correlation = correlation(),
                causation = causation(),
            ),
        )
    }

    companion object {
        const val TYPE = "com.dynokore.gst.platform.PackDryRunSampleRecorded.v1"
        const val TOPIC = "gst.platform.pack-dry-run-sample-recorded.v1"
    }
}
