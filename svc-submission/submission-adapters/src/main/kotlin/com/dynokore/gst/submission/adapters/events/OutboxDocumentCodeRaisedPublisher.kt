package com.dynokore.gst.submission.adapters.events

import com.dynokore.gst.submission.application.arrival.CodeSignals
import com.dynokore.gst.submission.application.arrival.RaisedCode
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
 * [CodeSignals] over the transactional outbox (Richardson): `DocumentCodeRaised` is appended in the open transaction of
 * the caller, so a rollback leaves no event (INV-AC-21088). The Doc UUID is the aggregate key; the event carries no
 * transaction date because the raised code has none; `remediationOwner` and `disposition` are absent when null.
 */
class OutboxDocumentCodeRaisedPublisher(
    private val outbox: OutboxWriter,
    private val tenants: TenantContext,
) : CodeSignals {
    override fun stage(raised: RaisedCode) {
        outbox.append(
            listOf(OutboundEvent(EVENT_TYPE, payload(raised))),
            EventContext(
                tenant = tenants.current(),
                entity = EntityRef(raised.entityRef),
                docUuid = DocUuid(UUID.fromString(raised.docUuid)),
                transactionDate = null,
                aggregateKey = raised.docUuid,
                aggregateVersion = null,
                correlation = CorrelationId(UUID.randomUUID().toString()),
                causation = CausationId("code-raised:${raised.docUuid}:${raised.version}:${raised.code}"),
            ),
        )
    }

    private fun payload(raised: RaisedCode): String {
        val members = linkedMapOf<String, Any>()
        members["docUuid"] = raised.docUuid
        members["version"] = raised.version
        members["entityRef"] = raised.entityRef
        members["periodRef"] = raised.periodRef
        members["code"] = raised.code
        raised.remediationOwner?.let { members["remediationOwner"] = it }
        raised.disposition?.let { members["disposition"] = it }
        return MAPPER.writeValueAsString(members)
    }

    companion object {
        const val EVENT_TYPE = "com.dynokore.gst.submission.DocumentCodeRaised.v1"
        private val MAPPER: JsonMapper = JsonMapper.builder().build()
    }
}
