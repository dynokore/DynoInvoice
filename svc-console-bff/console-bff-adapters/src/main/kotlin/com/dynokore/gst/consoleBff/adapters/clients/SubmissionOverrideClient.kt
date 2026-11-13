package com.dynokore.gst.consoleBff.adapters.clients

import com.dynokore.gst.consoleBff.adapters.clients.submission.api.ReadinessApi
import com.dynokore.gst.consoleBff.adapters.clients.submission.model.FilingGateOverrideRequest
import com.dynokore.gst.consoleBff.application.override.OwnerReply
import com.dynokore.gst.consoleBff.application.override.Release
import com.dynokore.gst.consoleBff.application.override.SubmissionOverrides
import com.dynokore.platform.serviceruntime.resilience.ResilienceRegistry
import org.openapitools.client.infrastructure.RequestConfig
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Clock

private const val HTTP_OK = 200
private const val HTTP_CREATED = 201
private const val HTTP_ACCEPTED = 202

/**
 * ARC-08, TPL-07: the owner's operations getLatestReadinessCheck and overrideFilingGate of submission-svc
 * (`console-bff->submission-svc`), through the generated client and the policy of the edge. The `RestClient` comes
 * from the bean `internalRestClientBuilder`, whose interceptor sets the end user's token; this class sets no header.
 * The owner's refusal is relayed unchanged (RFC 9457); nothing is stored here (single writer per state).
 */
@Component
class SubmissionOverrideClient(
    @Qualifier("internalRestClientBuilder") builder: RestClient.Builder,
    registry: ResilienceRegistry,
    private val clock: Clock,
    environment: Environment,
) : SubmissionOverrides {
    private val restClient = OwningServiceEdge.restClient(builder, environment, OwningService.SUBMISSION)
    private val readiness = ReadinessApi(restClient)
    private val transport = OwnerTransport(restClient)
    private val policy = OwningServiceEdge.policy(registry, OwningService.SUBMISSION)

    override fun latestCheck(
        entityRef: String,
        periodRef: String,
    ): OwnerReply<String> =
        call({ readiness.getLatestReadinessCheckRequestConfig(entityRef, periodRef) }) { answer ->
            val checkId = OwnerEnvelope.tree(answer.body)?.let { OwnerEnvelope.text(it, "checkId") }
            if (answer.status == HTTP_OK && checkId != null) OwnerReply.Ok(checkId) else unreadable(answer.status)
        }

    override fun override(
        entityRef: String,
        periodRef: String,
        checkId: String,
        reason: String,
    ): OwnerReply<Release> {
        val body = FilingGateOverrideRequest(checkId = checkId, reason = reason)
        return call({ readiness.overrideFilingGateRequestConfig(entityRef, periodRef, body) }) { answer ->
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
        request: () -> RequestConfig<*>,
        onSuccess: (OwnerAnswer) -> OwnerReply<T>,
    ): OwnerReply<T> =
        ownerCall(
            OwningService.SUBMISSION.edge,
            OwningService.SUBMISSION.timeout,
            policy,
            clock,
            transport,
            request,
            onSuccess,
        )

    private fun unreadable(status: Int) =
        OwnerReply.Unavailable("${OwningService.SUBMISSION.edge}: unreadable answer (status $status)")
}
