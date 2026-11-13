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
    private val clock: Clock,
    environment: Environment,
) : PayloadOverrides {
    private val restClient = OwningServiceEdge.restClient(builder, environment, OwningService.PAYLOAD)
    private val queries = QueriesApi(restClient)
    private val validation = ValidationApi(restClient)
    private val transport = OwnerTransport(restClient)
    private val policy = OwningServiceEdge.policy(registry, OwningService.PAYLOAD)

    override fun latestVersion(docUuid: String): OwnerReply<Long> {
        val id = parseUuid(docUuid) ?: return OwnerReply.Unavailable("${OwningService.PAYLOAD.edge}: docUuid is not a UUID")
        return call({ queries.getLatestDocumentVersionRequestConfig(id) }) { answer ->
            val version = OwnerEnvelope.tree(answer.body)?.let { OwnerEnvelope.integer(it, "version") }
            if (answer.status == HTTP_OK && version != null) OwnerReply.Ok(version) else unreadable(answer.status)
        }
    }

    override fun override(
        docUuid: String,
        version: Long,
        code: String,
        reason: String,
    ): OwnerReply<Release> {
        val id = parseUuid(docUuid) ?: return OwnerReply.Unavailable("${OwningService.PAYLOAD.edge}: docUuid is not a UUID")
        val versionInt = version.toInt().takeIf { it.toLong() == version }
            ?: return OwnerReply.Unavailable("${OwningService.PAYLOAD.edge}: version out of range")
        val body = OverrideValidationRequest(code = code, reason = reason)
        return call({ validation.overrideValidationRequestConfig(id, versionInt, body) }) { answer ->
            val tree = OwnerEnvelope.tree(answer.body)
            val released = tree?.let { OwnerEnvelope.instant(it, "overriddenAt") }
            val pending = tree?.let { OwnerEnvelope.instant(it, "requestedAt") }
            when {
                answer.status == HTTP_CREATED && released != null -> OwnerReply.Ok(Release.Released(released))
                answer.status == HTTP_ACCEPTED && pending != null -> OwnerReply.Ok(Release.Pending(pending))
                else -> unreadable(answer.status)
            }
        }
    }

    private fun <T> call(
        request: () -> org.openapitools.client.infrastructure.RequestConfig<*>,
        onSuccess: (OwnerAnswer) -> OwnerReply<T>,
    ): OwnerReply<T> =
        ownerCall(
            OwningService.PAYLOAD.edge,
            OwningService.PAYLOAD.timeout,
            policy,
            clock,
            transport,
            request,
            onSuccess,
        )

    private fun unreadable(status: Int) =
        OwnerReply.Unavailable("${OwningService.PAYLOAD.edge}: unreadable answer (status $status)")

    private fun parseUuid(text: String): UUID? = runCatching { UUID.fromString(text) }.getOrNull()
}
