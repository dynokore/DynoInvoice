package com.dynokore.gst.consoleBff.adapters.clients

import com.dynokore.gst.consoleBff.adapters.clients.submission.api.TransactionsApi
import com.dynokore.gst.consoleBff.adapters.clients.submission.model.BoundaryCheckRequest
import com.dynokore.gst.consoleBff.application.override.OwnerReply
import com.dynokore.gst.consoleBff.application.resolvegroup.BoundaryResult
import com.dynokore.gst.consoleBff.application.resolvegroup.SubmissionBoundary
import com.dynokore.gst.consoleBff.application.resolvegroup.SubmittedMember
import com.dynokore.platform.serviceruntime.resilience.ResilienceRegistry
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import java.time.Clock
import java.util.UUID

private const val HTTP_OK = 200

/**
 * ARC-08, TPL-07: the owner's read checkSubmissionBoundary of submission-svc (`console-bff->submission-svc`),
 * through the generated client and the policy of the edge. The token comes from the interceptor of the bean
 * `internalRestClientBuilder`; this class sets no header. The owner's refusal is relayed unchanged (RFC 9457).
 */
@Component
class SubmissionBoundaryClient(
    @Qualifier("internalRestClientBuilder") builder: RestClient.Builder,
    registry: ResilienceRegistry,
    clock: Clock,
    environment: Environment,
) : SubmissionBoundary {
    private val restClient = OwningServiceEdge.restClient(builder, environment, OwningService.SUBMISSION)
    private val transactions = TransactionsApi(restClient)
    private val edge = OwnerEdge(OwningService.SUBMISSION, registry, clock, restClient)

    override fun check(docUuids: List<String>): OwnerReply<BoundaryResult> {
        val ids = docUuids.map { runCatching { UUID.fromString(it) }.getOrNull() }
        return if (ids.any { it == null }) {
            OwnerReply.Unavailable("${OwningService.SUBMISSION.edge}: docUuid is not a UUID")
        } else {
            val body = BoundaryCheckRequest(docUuids = ids.filterNotNull())
            edge.call({ transactions.checkSubmissionBoundaryRequestConfig(body) }) { answer ->
                val result = if (answer.status == HTTP_OK) result(answer.body) else null
                if (result != null) OwnerReply.Ok(result) else edge.unreadable(answer.status)
            }
        }
    }

    /** Every member must be present and well-typed; none is filled with a default (ASVS V4.1.1: fail closed). */
    private fun result(raw: String?): BoundaryResult? {
        val tree = OwnerEnvelope.tree(raw) ?: return null
        val checked = OwnerEnvelope.integer(tree, "checkedCount")?.toIntOrNull()
        val submitted = OwnerEnvelope.integer(tree, "submittedCount")?.toIntOrNull()
        val members = members(tree.get("submittedMembers"))
        return if (checked == null || submitted == null || members == null) {
            null
        } else {
            BoundaryResult(checked, submitted, members)
        }
    }

    private fun members(array: JsonNode?): List<SubmittedMember>? {
        if (array == null || !array.isArray) return null
        val read = (0 until array.size()).map { member(array.get(it)) }
        return if (read.contains(null)) null else read.filterNotNull()
    }

    private fun member(node: JsonNode): SubmittedMember? {
        val doc = OwnerEnvelope.text(node, "docUuid")
        val state = OwnerEnvelope.text(node, "state")
        return if (doc == null || state == null) null else SubmittedMember(doc, state)
    }

    private fun Long.toIntOrNull(): Int? = takeIf { it.toInt().toLong() == it }?.toInt()
}
