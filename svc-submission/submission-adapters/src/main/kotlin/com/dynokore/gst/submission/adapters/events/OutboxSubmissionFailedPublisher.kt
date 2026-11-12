package com.dynokore.gst.submission.adapters.events

import com.dynokore.gst.submission.application.queue.FailureSignals
import com.dynokore.gst.submission.application.queue.SubmissionFailure
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
import java.util.UUID

/**
 * [FailureSignals] over the transactional outbox (Richardson): the `SubmissionFailed` event is appended in the open
 * transaction of the outcome, so a rollback leaves no event (INV-REQ-940). The entity and transaction date come from
 * the aggregate row, the Doc UUID is the aggregate key; a member that is null is absent from the payload.
 */
class OutboxSubmissionFailedPublisher(
    private val outbox: OutboxWriter,
    private val tenants: TenantContext,
    private val store: SubmissionStore,
) : FailureSignals {
    override fun stage(failure: SubmissionFailure) {
        val tenant = tenants.current()
        val row =
            checkNotNull(store.lockForUpdate(tenant, failure.docUuid, failure.version)) {
                "submission ${failure.docUuid} version ${failure.version} does not exist"
            }
        outbox.append(
            listOf(OutboundEvent(EVENT_TYPE, payload(failure))),
            EventContext(
                tenant = tenant,
                entity = EntityRef(row.entityRef),
                docUuid = DocUuid(UUID.fromString(failure.docUuid)),
                transactionDate = row.transactionDate,
                aggregateKey = failure.docUuid,
                aggregateVersion = null,
                correlation = CorrelationId(UUID.randomUUID().toString()),
                causation = CausationId("attempt-outcome:${failure.docUuid}:${failure.version}"),
            ),
        )
    }

    private fun payload(failure: SubmissionFailure): String {
        val members = linkedMapOf<String, Any>()
        members["docUuid"] = failure.docUuid
        members["version"] = failure.version
        members["failureClass"] = failure.failureClass
        members["retryDisposition"] = failure.retryDisposition
        failure.code?.let { members["code"] = it }
        failure.remediationOwner?.let { members["remediationOwner"] = it }
        failure.disposition?.let { members["disposition"] = it }
        return MAPPER.writeValueAsString(members)
    }

    companion object {
        const val EVENT_TYPE = "com.dynokore.gst.submission.SubmissionFailed.v1"
        private val MAPPER: JsonMapper = JsonMapper.builder().build()
    }
}
