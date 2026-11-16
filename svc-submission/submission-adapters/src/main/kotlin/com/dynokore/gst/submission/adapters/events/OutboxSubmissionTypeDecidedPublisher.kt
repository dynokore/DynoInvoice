package com.dynokore.gst.submission.adapters.events

import com.dynokore.gst.submission.adapters.persistence.EntityConnections
import com.dynokore.gst.submission.application.typing.TypeDecidedFacts
import com.dynokore.gst.submission.application.typing.TypeDecisionSignals
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import java.util.Locale
import java.util.UUID

/**
 * [TypeDecisionSignals] over the transactional outbox (Richardson): `SubmissionTypeDecided` is appended in the open
 * transaction of the caller, so a rollback leaves no event (INV-REQ-415). The payload `docsequence` is the number
 * `appendSequenced` reserves for the same row; this class reads no counter. The Doc UUID is the aggregate key.
 */
class OutboxSubmissionTypeDecidedPublisher(
    private val outbox: OutboxWriter,
    private val tenants: TenantContext,
    private val connections: EntityConnections,
) : TypeDecisionSignals {
    override fun stage(facts: TypeDecidedFacts) {
        val tenant = tenants.current()
        // Read in the caller's transaction from the replica; no call to another service.
        val connectionRef = connections.connectionOf(tenant, facts.entityRef)
        outbox.appendSequenced(
            EVENT_TYPE,
            EventContext(
                tenant = tenant,
                entity = EntityRef(facts.entityRef),
                docUuid = DocUuid(UUID.fromString(facts.docUuid)),
                transactionDate = null,
                aggregateKey = facts.docUuid,
                aggregateVersion = null,
                correlation = CorrelationId(UUID.randomUUID().toString()),
                causation = CausationId("type-decided:${facts.docUuid}:${facts.version}"),
            ),
        ) { docSequence -> payload(facts, docSequence, connectionRef) }
    }

    private fun payload(
        facts: TypeDecidedFacts,
        docSequence: Long,
        connectionRef: String?,
    ): String =
        "{\"docUuid\":${json(facts.docUuid)},\"version\":${facts.version},\"docsequence\":$docSequence," +
            "\"submissionType\":${json(facts.submissionType)}" +
            (if (connectionRef == null) "" else ",\"connectionRef\":${json(connectionRef)}") + "}"

    private fun json(text: String): String =
        buildString {
            append('"')
            text.forEach { c ->
                when {
                    c == '"' -> append("\\\"")
                    c == '\\' -> append("\\\\")
                    c < ' ' -> append(String.format(Locale.ROOT, "\\u%04x", c.code))
                    else -> append(c)
                }
            }
            append('"')
        }

    companion object {
        const val EVENT_TYPE = "com.dynokore.gst.submission.SubmissionTypeDecided.v1"
    }
}
