package com.dynokore.gst.canonical.adapters.events

import com.dynokore.gst.canonical.application.document.CanonicalVersionCreatedFacts
import com.dynokore.gst.canonical.application.document.CanonicalVersionCreatedPublisher
import com.dynokore.gst.canonical.application.document.RoutingFactsData
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import java.util.Locale

/**
 * Stages CanonicalVersionCreated through the transactional outbox in the caller's transaction (INV-REQ-439). The
 * payload `docsequence` is the number `appendSequenced` reserves for the same row; this class reads no counter.
 * Optional members are written only when known: never a JSON null and never an empty string.
 */
class OutboxCanonicalVersionCreatedPublisher(
    private val outbox: OutboxWriter,
    private val tenants: TenantContext,
    private val correlation: () -> CorrelationId,
    private val causation: () -> CausationId,
) : CanonicalVersionCreatedPublisher {
    override fun stage(facts: CanonicalVersionCreatedFacts) {
        outbox.appendSequenced(
            TYPE,
            EventContext(
                tenant = tenants.current(),
                entity = EntityRef(facts.entityRef),
                docUuid = DocUuid(facts.docUuid),
                transactionDate = facts.transactionDate,
                aggregateKey = facts.docUuid.toString(),
                aggregateVersion = facts.version.toLong(),
                correlation = correlation(),
                causation = causation(),
            ),
        ) { docSequence -> payload(facts, docSequence) }
    }

    private fun payload(
        facts: CanonicalVersionCreatedFacts,
        docSequence: Long,
    ): String =
        buildString {
            append("{\"docUuid\":").append(json(facts.docUuid.toString()))
            append(",\"version\":").append(facts.version)
            append(",\"docsequence\":").append(docSequence)
            append(",\"transactionDate\":").append(json(facts.transactionDate.toString()))
            append(",\"prescribedPeriodRef\":").append(json(facts.prescribedPeriodRef))
            append(",\"scopeVerdict\":").append(json(facts.scopeVerdict))
            append(",\"appliedPackVersions\":")
            append(
                facts.appliedPackVersions.joinToString(",", "[", "]") {
                    "{\"packIdentifier\":${json(it.packIdentifier)},\"version\":${json(it.version)}}"
                },
            )
            append(",\"traceReference\":").append(json(facts.traceReference))
            append(",\"canonicalDocument\":").append(facts.canonicalDocument)
            facts.aggregateId?.let { append(",\"aggregateId\":").append(json(it)) }
            append(",\"routingFacts\":").append(routing(facts.routingFacts))
            append('}')
        }

    private fun routing(routing: RoutingFactsData): String =
        buildString {
            append("{\"direction\":").append(json(routing.direction.name))
            append(",\"networkPath\":").append(json(routing.networkPath.name))
            routing.counterpartyOnNetwork?.let { append(",\"counterpartyOnNetwork\":").append(it) }
            routing.debitNote?.let { append(",\"debitNote\":").append(it) }
            routing.selfBillingAssignment?.let {
                append(",\"selfBillingAssignment\":{\"direction\":").append(json(it.direction.name))
                append(",\"submissionType\":").append(json(it.submissionType.name)).append('}')
            }
            append('}')
        }

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

    private companion object {
        const val TYPE = "com.dynokore.gst.canonical.CanonicalVersionCreated.v1"
    }
}
