package com.dynokore.gst.consoleBff.adapters.clients

import com.dynokore.gst.consoleBff.adapters.clients.masterdata.api.RemediationApi
import com.dynokore.gst.consoleBff.adapters.clients.masterdata.model.BulkResolveDocument
import com.dynokore.gst.consoleBff.adapters.clients.masterdata.model.BulkResolveRequest
import com.dynokore.gst.consoleBff.adapters.clients.masterdata.model.ResolveCounterpartyRequestResolvedIdentifiersInner
import com.dynokore.gst.consoleBff.application.override.OwnerReply
import com.dynokore.gst.consoleBff.application.resolvegroup.BulkCommand
import com.dynokore.gst.consoleBff.application.resolvegroup.BulkOutcome
import com.dynokore.gst.consoleBff.application.resolvegroup.MasterdataBulk
import com.dynokore.platform.serviceruntime.resilience.ResilienceRegistry
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Clock
import java.util.UUID

private const val HTTP_OK = 200

/**
 * ARC-08, TPL-07: the owner's write bulkResolveCounterparty of masterdata-svc (`console-bff->masterdata-svc`),
 * through the generated client and the policy of the edge. The token comes from the interceptor of the bean
 * `internalRestClientBuilder`; this class sets no header of its own beyond the generated Idempotency-Key. The
 * owner's refusal is relayed unchanged (RFC 9457); nothing is stored here (single writer per state).
 */
@Component
class MasterdataBulkClient(
    @Qualifier("internalRestClientBuilder") builder: RestClient.Builder,
    registry: ResilienceRegistry,
    clock: Clock,
    environment: Environment,
) : MasterdataBulk {
    private val restClient = OwningServiceEdge.restClient(builder, environment, OwningService.MASTERDATA)
    private val remediation = RemediationApi(restClient)
    private val edge = OwnerEdge(OwningService.MASTERDATA, registry, clock, restClient)

    override fun resolve(command: BulkCommand): OwnerReply<BulkOutcome> {
        val key = runCatching { UUID.fromString(command.idempotencyKey) }.getOrNull()
        val ids = command.docUuids.map { runCatching { UUID.fromString(it) }.getOrNull() }
        return if (key == null || ids.any { it == null }) {
            OwnerReply.Unavailable("${OwningService.MASTERDATA.edge}: idempotency key or docUuid is not a UUID")
        } else {
            val docs = ids.filterNotNull()
            val body =
                BulkResolveRequest(
                    errorCode = command.errorCode,
                    docUuids = docs,
                    resolvedIdentifiers =
                        command.resolvedIdentifiers.map {
                            ResolveCounterpartyRequestResolvedIdentifiersInner(it.scheme, it.value)
                        },
                    evidenceRef = command.evidenceRef,
                    entityRef = command.entityRef,
                    documents = docs.map { BulkResolveDocument(it, command.counterpartyRef) },
                )
            edge.call({ remediation.bulkResolveCounterpartyRequestConfig(key, body) }) { answer ->
                val outcome = if (answer.status == HTTP_OK) outcome(answer.body) else null
                if (outcome != null) OwnerReply.Ok(outcome) else edge.unreadable(answer.status)
            }
        }
    }

    private fun outcome(raw: String?): BulkOutcome? {
        val tree = OwnerEnvelope.tree(raw) ?: return null
        val resolved = OwnerEnvelope.integer(tree, "resolvedCount")?.asInt()
        val unchanged = OwnerEnvelope.integer(tree, "unchangedCount")?.asInt()
        return if (resolved == null || unchanged == null) null else BulkOutcome(resolved, unchanged)
    }

    private fun Long.asInt(): Int? = takeIf { it.toInt().toLong() == it }?.toInt()
}
