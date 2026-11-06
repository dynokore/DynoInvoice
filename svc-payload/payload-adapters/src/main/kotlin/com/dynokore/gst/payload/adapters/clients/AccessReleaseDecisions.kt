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
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.env.Environment
import org.springframework.http.ResponseEntity
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
 * `Denied(ACCESS_DECISION_UNAVAILABLE)`; only an exact `ALLOW` with the outcome `RELEASED` is [ReleaseOutcome.Released].
 *
 * Not a scanned component: the bean and its property `payload.access-svc-url` are registered by the wiring slice
 * (INV-T-10713), so no context without that property tries to build it.
 */
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
                onCallNotPermitted = { unavailable() },
                onConnectFailure = { unavailable() },
                onTimeout = { unavailable() },
            ) {
                ask(request)
            }
        } catch (_: Exception) {
            // The surfaced 5xx (after the edge's retry) and any other failure: a denial, never an exception or a release
            unavailable()
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
                // INV-Q-17337 A: a 5xx surfaces (the breaker records it, the edge retries it); a 4xx is our request
                // refused: it is answered inside the block, so it is a success for the breaker and is not retried
                if (!failure.statusCode.is4xxClientError) throw failure
                // the status and the operation only, never the body of the answer
                log.warn("decideOverrideRelease answered HTTP {}", failure.statusCode.value())
                return unavailable()
            }
        val tree = readTree(raw)
        val decision = tree?.let { text(it, "decision") }
        // a body that is not a JSON object with a string `decision` cannot be deserialized into the generated model
        return if (tree == null || decision == null) {
            unavailable()
        } else {
            ReleaseOutcome.from(decision, text(tree, "outcome"), text(tree, "reasonCode"))
        }
    }

    private fun readTree(raw: String?): JsonNode? =
        raw?.takeIf { it.isNotBlank() }?.let { runCatching { mapper.readTree(it) }.getOrNull() }?.takeIf { it.isObject }

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

    /** INV-Q-17335 A: the call itself failed; the use cases read it as Refused (403), never NotOverridable. */
    private fun unavailable(): ReleaseOutcome = ReleaseOutcome.Denied(ACCESS_DECISION_UNAVAILABLE)

    companion object {
        /** The reason code of a failed call: a payload-svc code, not a value of the access contract (INV-Q-17335 A). */
        const val ACCESS_DECISION_UNAVAILABLE = "ACCESS_DECISION_UNAVAILABLE"

        private val log = LoggerFactory.getLogger(AccessReleaseDecisions::class.java)

        private const val URL_PROPERTY = "payload.access-svc-url"
        private const val EDGE = "payload-svc->access-svc"

        /** `x-timeout` of `decideOverrideRelease` in the access contract (sync-edges.yaml: 500 ms). */
        private val EDGE_TIMEOUT: Duration = Duration.ofMillis(500)

        /** Required and not blank, with no default (TPL-16, twelve-factor config): the context fails without it. */
        private fun baseUrl(environment: Environment): String =
            environment.getRequiredProperty(URL_PROPERTY).also {
                require(it.isNotBlank()) { "$URL_PROPERTY must not be blank" }
            }
    }
}
