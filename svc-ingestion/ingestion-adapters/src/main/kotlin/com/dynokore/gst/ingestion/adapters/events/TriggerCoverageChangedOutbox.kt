package com.dynokore.gst.ingestion.adapters.events

import com.dynokore.gst.ingestion.application.triggers.TriggerCoverageChanged
import com.dynokore.gst.ingestion.application.triggers.TriggerCoverageEvents
import com.dynokore.gst.ingestion.domain.triggers.CoverageRecord
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
 * Writes `TriggerCoverageChanged` (event-carried state transfer to tenancy-svc, INV-REQ-788) to the outbox of the
 * caller's open transaction, so the row and its event commit or vanish together. The payload follows the
 * `TriggerCoverageChanged` message of the ingestion AsyncAPI contract; the tenant travels in the envelope and the
 * acting user is carried by id, never a role (INV-Q-10647). It is a plain class: the composition root binds it.
 */
class TriggerCoverageChangedOutbox(
    private val outbox: OutboxWriter,
    private val clock: Clock,
) : TriggerCoverageEvents {
    override fun publish(event: TriggerCoverageChanged) {
        val key = "${event.entityRef}:${event.triggerClass.name}"
        val correlation = UUID.randomUUID().toString()
        outbox.append(
            listOf(OutboundEvent(TYPE, payload(event))),
            EventContext(
                TenantId(event.tenantId),
                EntityRef(event.entityRef),
                null,
                null,
                key,
                null,
                CorrelationId(correlation),
                CausationId(correlation),
            ),
        )
    }

    private fun payload(event: TriggerCoverageChanged): String {
        val node = MAPPER.createObjectNode()
        node.put("entityRef", event.entityRef)
        node.put("triggerClass", event.triggerClass.name)
        when (val record = event.record) {
            is CoverageRecord.Mapped -> {
                node.put("status", "MAPPED")
                node.put("adapterRef", record.adapterRef)
            }
            is CoverageRecord.Waived -> {
                node.put("status", "WAIVED")
                node.put("reviewDate", record.reviewDate.toString())
            }
            is CoverageRecord.CoveredElsewhere -> {
                node.put("status", "COVERED_BY_OTHER_SOLUTION")
                node.put("coveringSolution", record.coveringSolution)
                node.put("evidenceReference", record.evidenceReference)
            }
        }
        node.put("changedAt", clock.instant().toString())
        node.put("actorUserId", event.actorUserId.toString())
        return MAPPER.writeValueAsString(node)
    }

    companion object {
        const val TYPE = "com.dynokore.gst.ingestion.TriggerCoverageChanged.v1"
        const val TOPIC = "gst.ingestion.trigger-coverage-changed.v1"
        private val MAPPER: JsonMapper = JsonMapper.builder().build()
    }
}
