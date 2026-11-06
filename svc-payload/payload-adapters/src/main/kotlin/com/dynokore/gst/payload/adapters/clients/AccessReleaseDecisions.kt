package com.dynokore.gst.payload.adapters.clients

import com.dynokore.gst.payload.adapters.clients.api.SeparationOfDutyApi
import com.dynokore.gst.payload.application.override.ReleaseDecisions
import com.dynokore.gst.payload.application.override.ReleaseRequest
import com.dynokore.gst.payload.domain.override.ReleaseOutcome
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.serviceruntime.resilience.ResilienceRegistry
import com.dynokore.platform.serviceruntime.resilience.http.CurrentDeadline
import org.openapitools.client.infrastructure.ApiClient
import org.openapitools.client.infrastructure.RequestConfig
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.env.Environment
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration

/**
 * INV-REQ-1293, INV-Q-17175 A, ARC-08: asks `decideOverrideRelease` of access-svc over HTTP, through the client
 * generated from the owner's contract (STK-46) and the policy of the sync edge. Fail closed ("Deny human action"): a
 * 4xx or 5xx answer, a timeout, an open breaker, a full bulkhead, no connection and an unreadable body are each
 * [ReleaseOutcome.Denied]; only an exact `ALLOW` with the outcome `RELEASED` is [ReleaseOutcome.Released].
 */
@Component
class AccessReleaseDecisions(
    @Qualifier("internalRestClientBuilder") builder: RestClient.Builder,
    registry: ResilienceRegistry,
    private val clock: Clock,
    environment: Environment,
) : ReleaseDecisions {
    private val restClient = builder.baseUrl(baseUrl(environment)).build()
    private val api = SeparationOfDutyApi(restClient)
    private val transport = Transport(restClient)
    private val mapper = JsonMapper.builder().build()
    private val policy = registry.forEdge(EDGE)

    override fun decide(request: ReleaseRequest): ReleaseOutcome {
        // API-29: the deadline of the request being served; a call with none behind it gets the edge's contract timeout
        val deadline = CurrentDeadline.current() ?: Deadline.after(EDGE_TIMEOUT, clock)
        return try {
            policy.execute(
                deadline,
                onCallNotPermitted = { denied() },
                onConnectFailure = { denied() },
                onTimeout = { denied() },
            ) {
                ask(request)
            }
        } catch (_: Exception) {
            // Any other failure (an unreadable body, an unexpected exception) is a denial as well: never a release
            denied()
        }
    }

    private fun ask(request: ReleaseRequest): ReleaseOutcome {
        // Method, path, headers and query come from the generated config. The body is read raw: the generated enums
        // are lenient (`ALLOW ` decodes to ALLOW), so `decision`, `outcome` and `reasonCode` are compared as strings.
        val config =
            api.decideOverrideReleaseRequestConfig(
                entityRef = request.entityRef,
                errorCode = request.errorCode,
                approverRef = request.approverRef,
                documentRef = request.docUuid.toString(),
                documentVersion = request.version,
                documentTotalSgd = request.totalSgd,
                priorApproverRef = request.priorApproverRefs.takeIf { it.isNotEmpty() },
            )
        val raw =
            try {
                transport.get(config).body
            } catch (failure: RestClientResponseException) {
                // a 4xx is our request refused, not an outage of the edge: it is a denial and does not trip the breaker
                if (failure.statusCode.is4xxClientError) return denied()
                throw failure
            }
        val tree = raw?.takeIf { it.isNotBlank() }?.let { mapper.readTree(it) } ?: return denied()
        if (!tree.isObject) return denied()
        return ReleaseOutcome.from(text(tree, "decision"), text(tree, "outcome"), text(tree, "reasonCode"))
    }

    /** The member as the exact string it holds; absent, null or not a string is nothing. */
    private fun text(
        tree: JsonNode,
        name: String,
    ): String? = tree.get(name)?.takeIf { it.isString }?.asString()

    /** The generated `ApiClient` sends the request; the answer comes back raw, so nothing is decoded leniently. */
    private class Transport(
        client: RestClient,
    ) : ApiClient(client) {
        fun get(config: RequestConfig<Unit>): ResponseEntity<String> =
            prepare(defaults(config)).retrieve().toEntity(String::class.java)
    }

    private fun denied(): ReleaseOutcome = ReleaseOutcome.Denied(ReleaseOutcome.NOT_OVERRIDABLE)

    private companion object {
        const val URL_PROPERTY = "payload.access-svc-url"
        const val EDGE = "payload-svc->access-svc"

        /** `x-timeout` of `decideOverrideRelease` in the access contract (sync-edges.yaml: 500 ms). */
        val EDGE_TIMEOUT: Duration = Duration.ofMillis(500)

        /** Required and not blank, with no default (TPL-16, twelve-factor config): the context fails without it. */
        fun baseUrl(environment: Environment): String =
            environment.getRequiredProperty(URL_PROPERTY).also {
                require(it.isNotBlank()) { "$URL_PROPERTY must not be blank" }
            }
    }
}
