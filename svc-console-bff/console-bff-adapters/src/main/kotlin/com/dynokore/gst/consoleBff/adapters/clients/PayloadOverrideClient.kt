package com.dynokore.gst.consoleBff.adapters.clients

import com.dynokore.gst.consoleBff.adapters.clients.payload.api.QueriesApi
import com.dynokore.gst.consoleBff.adapters.clients.payload.api.ValidationApi
import com.dynokore.gst.consoleBff.adapters.clients.payload.model.OverrideValidationRequest
import com.dynokore.gst.consoleBff.application.override.OwnerReply
import com.dynokore.gst.consoleBff.application.override.PayloadOverrides
import com.dynokore.gst.consoleBff.application.override.Release
import com.dynokore.platform.serviceruntime.resilience.ResilienceRegistry
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Clock
import java.util.UUID

private const val HTTP_OK = 200
private const val HTTP_CREATED = 201
private const val HTTP_ACCEPTED = 202

/**
 * ARC-08, TPL-07: the owner's operations getLatestDocumentVersion and overrideValidation of payload-svc
 * (`console-bff->payload-svc`), through the generated client and the policy of the edge. The `RestClient` comes
 * from the bean `internalRestClientBuilder`, whose interceptor sets the end user's token; this class sets no header.
 * The owner's refusal is relayed unchanged (RFC 9457); nothing is stored here (single writer per state).
 */
@Component
class PayloadOverrideClient(
    @Qualifier("internalRestClientBuilder") builder: RestClient.Builder,
    registry: ResilienceRegistry,
    clock: Clock,
    environment: Environment,
) : PayloadOverrides {
    private val restClient = OwningServiceEdge.restClient(builder, environment, OwningService.PAYLOAD)
    private val queries = QueriesApi(restClient)
    private val validation = ValidationApi(restClient)
    private val edge = OwnerEdge(OwningService.PAYLOAD, registry, clock, restClient)

    override fun latestVersion(docUuid: String): OwnerReply<Long> =
        withDoc(docUuid) { id ->
            edge.call({ queries.getLatestDocumentVersionRequestConfig(id) }) { answer ->
                val version = OwnerEnvelope.tree(answer.body)?.let { OwnerEnvelope.integer(it, "version") }
                if (answer.status == HTTP_OK && version != null) {
                    OwnerReply.Ok(version)
                } else {
                    edge.unreadable(answer.status)
                }
            }
        }

    override fun override(
        docUuid: String,
        version: Long,
        code: String,
        reason: String,
    ): OwnerReply<Release> =
        withDoc(docUuid) { id ->
            val body = OverrideValidationRequest(code = code, reason = reason)
            if (version.toInt().toLong() != version) {
                OwnerReply.Unavailable("${OwningService.PAYLOAD.edge}: version out of range")
            } else {
                edge.call({ validation.overrideValidationRequestConfig(id, version.toInt(), body) }) { answer ->
                    released(answer)
                }
            }
        }

    private fun released(answer: OwnerAnswer): OwnerReply<Release> {
        val tree = OwnerEnvelope.tree(answer.body)
        val overriddenAt = tree?.let { OwnerEnvelope.instant(it, "overriddenAt") }
        val requestedAt = tree?.let { OwnerEnvelope.instant(it, "requestedAt") }
        return when {
            answer.status == HTTP_CREATED && overriddenAt != null -> OwnerReply.Ok(Release.Released(overriddenAt))
            answer.status == HTTP_ACCEPTED && requestedAt != null -> OwnerReply.Ok(Release.Pending(requestedAt))
            else -> edge.unreadable(answer.status)
        }
    }

    /** A document id that is not a UUID is never sent: the owner's path takes a UUID. */
    private fun <T> withDoc(
        docUuid: String,
        block: (UUID) -> OwnerReply<T>,
    ): OwnerReply<T> {
        val id = runCatching { UUID.fromString(docUuid) }.getOrNull()
        return if (id == null) {
            OwnerReply.Unavailable("${OwningService.PAYLOAD.edge}: docUuid is not a UUID")
        } else {
            block(id)
        }
    }
}
