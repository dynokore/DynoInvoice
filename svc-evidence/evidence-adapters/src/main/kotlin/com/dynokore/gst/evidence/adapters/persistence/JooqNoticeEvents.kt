package com.dynokore.gst.evidence.adapters.persistence

import com.dynokore.gst.evidence.application.cases.NoticeEvents
import com.dynokore.gst.evidence.domain.cases.CaseRecord
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantId

/**
 * Records `PrivacyNoticeDue` (INV-REQ-1403, INV-Q-6851 A) through the transactional outbox, in the caller's
 * transaction. It is a platform event: the entity is [EntityRef.TENANT_WIDE], since a domain event cannot carry it.
 * The payload holds the five fields of `privacy-notice-due-payload.schema.json` and nothing else: no subject and no
 * request reference. The service sends no message itself; the notification service consumes the event.
 */
class JooqNoticeEvents(
    private val outbox: OutboxWriter,
) : NoticeEvents {
    override fun record(
        tenant: TenantId,
        case: CaseRecord,
        noticeLeadDays: Int,
    ) {
        val privacy = checkNotNull(case.privacy) { "case ${case.caseId} has no privacy facts" }
        val caseId = case.caseId.toString()
        val payload =
            "{\"caseId\":\"$caseId\",\"requestKind\":\"${privacy.requestKind.name}\"," +
                "\"receivedOn\":\"${privacy.receivedOn}\",\"responseDeadline\":\"${privacy.responseDeadline}\"," +
                "\"noticeLeadDays\":$noticeLeadDays}"
        outbox.append(
            listOf(OutboundEvent(EVENT_TYPE, payload)),
            EventContext(
                tenant,
                EntityRef.TENANT_WIDE,
                null,
                null,
                caseId,
                null,
                CorrelationId(caseId),
                CausationId(caseId),
            ),
        )
    }

    companion object {
        const val EVENT_TYPE = "com.dynokore.gst.platform.PrivacyNoticeDue.v1"
    }
}
