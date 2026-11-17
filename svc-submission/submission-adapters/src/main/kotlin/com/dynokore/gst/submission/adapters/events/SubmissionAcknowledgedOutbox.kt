package com.dynokore.gst.submission.adapters.events

import com.dynokore.gst.submission.application.queue.AcknowledgedFact
import com.dynokore.gst.submission.application.queue.AcknowledgedPublisher
import com.dynokore.gst.submission.application.queue.SubmissionStore
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import tools.jackson.databind.json.JsonMapper
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * [AcknowledgedPublisher] over the transactional outbox (INV-ADR-283): `SubmissionAcknowledged` is appended in the
 * open transaction of the handler, so a rollback leaves no event. The entity and transaction date come from the
 * locked aggregate row; the payload is docUuid, version, acknowledgementId and timestamp (date-time, UTC).
 */
class SubmissionAcknowledgedOutbox(
    private val outbox: OutboxWriter,
    private val tenants: TenantContext,
    private val store: SubmissionStore,
) : AcknowledgedPublisher {
    override fun stage(fact: AcknowledgedFact) {
        val tenant = tenants.current()
        val row =
            checkNotNull(store.lockForUpdate(tenant, fact.docUuid, fact.version)) {
                "submission ${fact.docUuid} version ${fact.version} does not exist"
            }
        outbox.append(
            listOf(OutboundEvent(EVENT_TYPE, payload(fact))),
            EventContext(
                tenant = tenant,
                entity = EntityRef(row.entityRef),
                docUuid = DocUuid(UUID.fromString(fact.docUuid)),
                transactionDate = row.transactionDate,
                aggregateKey = fact.docUuid,
                aggregateVersion = null,
                correlation = CorrelationId(UUID.randomUUID().toString()),
                causation = CausationId("acknowledgement:${fact.docUuid}:${fact.version}"),
            ),
        )
    }

    private fun payload(fact: AcknowledgedFact): String =
        MAPPER.writeValueAsString(
            linkedMapOf(
                "docUuid" to fact.docUuid,
                "version" to fact.version,
                "acknowledgementId" to fact.acknowledgementId,
                "timestamp" to DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(fact.timestamp.atOffset(ZoneOffset.UTC)),
            ),
        )

    companion object {
        const val EVENT_TYPE = "com.dynokore.gst.submission.SubmissionAcknowledged.v1"
        private val MAPPER: JsonMapper = JsonMapper.builder().build()
    }
}
