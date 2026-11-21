package com.dynokore.gst.canonical.adapters.events

import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** One page of `PackReprocessingPopulationRecorded`; [payload] is the payload of the event. */
data class ReprocessingPage(
    val reprocessingId: String,
    val entityRef: String,
    val pageNumber: Int,
    val lastPage: Boolean,
    val documents: List<Map<String, Any>>,
) {
    fun payload(): Map<String, Any> =
        mapOf(
            "reprocessingId" to reprocessingId,
            "entityRef" to entityRef,
            "pageNumber" to pageNumber,
            "lastPage" to lastPage,
            "documents" to documents,
        )
}

/** Port: hands one page over (through the outbox). */
fun interface ReprocessingPagePublisher {
    fun publish(
        tenantId: UUID,
        page: ReprocessingPage,
    )
}

/**
 * Stages one `PackReprocessingPopulationRecorded` page (platform event, INV-REQ-1003) through the transactional outbox
 * in the caller's transaction. The entity of the event is the `entityRef` of the request, the tenant is the bound one;
 * a page for another tenant than the bound one is refused. The event is not document-scoped, so it has no
 * `docsequence`.
 */
class OutboxReprocessingPagePublisher(
    private val outbox: OutboxWriter,
    private val tenants: TenantContext,
    private val correlation: () -> CorrelationId,
    private val causation: () -> CausationId,
) : ReprocessingPagePublisher {
    private val json = JsonMapper.builder().build()

    override fun publish(
        tenantId: UUID,
        page: ReprocessingPage,
    ) {
        val bound = tenants.current()
        require(bound.value == tenantId) { "the page is for a tenant other than the bound one" }
        outbox.append(
            listOf(OutboundEvent(TYPE, json.writeValueAsString(page.payload()))),
            EventContext(
                tenant = bound,
                entity = EntityRef(page.entityRef),
                docUuid = null,
                transactionDate = null,
                aggregateKey = page.reprocessingId,
                aggregateVersion = null,
                correlation = correlation(),
                causation = causation(),
            ),
        )
    }

    companion object {
        const val TYPE = "com.dynokore.gst.platform.PackReprocessingPopulationRecorded.v1"
        const val TOPIC = "gst.platform.pack-reprocessing-population-recorded.v1"
    }
}
