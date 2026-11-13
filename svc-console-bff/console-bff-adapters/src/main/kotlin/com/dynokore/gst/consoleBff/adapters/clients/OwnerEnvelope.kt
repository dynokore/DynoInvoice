package com.dynokore.gst.consoleBff.adapters.clients

import com.dynokore.gst.consoleBff.application.override.OwnerRefusal
import com.dynokore.gst.consoleBff.application.override.OwnerReply
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.serviceruntime.resilience.ResilienceRegistry
import com.dynokore.platform.serviceruntime.resilience.http.CurrentDeadline
import org.openapitools.client.infrastructure.ApiClient
import org.openapitools.client.infrastructure.RequestConfig
import org.springframework.http.ResponseEntity
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * The error envelope of an owning service (RFC 9457 problem details with the platform members), read into the
 * [OwnerRefusal] of the use case. Unknown members are ignored. The status is the HTTP status; `code` and `title` are
 * required (the refusal has no null for them), so an envelope without one is unreadable; every other member stays
 * null when absent and none is filled (ASVS V4.1.1: fail closed).
 */
internal object OwnerEnvelope {
    private val mapper = JsonMapper.builder().build()

    fun refusal(
        status: Int,
        raw: String?,
    ): OwnerRefusal? {
        val root = tree(raw)
        val code = root?.let { text(it, "code") }
        val title = root?.let { text(it, "title") }
        return if (root == null || code == null || title == null) {
            null
        } else {
            OwnerRefusal(
                status = status,
                code = code,
                title = title,
                severity = text(root, "severity"),
                disposition = text(root, "disposition"),
                remediationOwner = text(root, "remediationOwner"),
                detail = text(root, "detail"),
            )
        }
    }

    /** The body as a JSON object, or null when it is blank, not JSON or not an object. */
    fun tree(raw: String?): JsonNode? =
        raw
            ?.takeIf { it.isNotBlank() }
            ?.let { parsed(it) }
            ?.takeIf { it.isObject }

    private fun parsed(raw: String): JsonNode? =
        try {
            mapper.readTree(raw)
        } catch (_: JacksonException) {
            null
        }

    fun text(
        node: JsonNode,
        member: String,
    ): String? = node.get(member)?.takeIf { it.isString }?.stringValue()

    fun integer(
        node: JsonNode,
        member: String,
    ): Long? = node.get(member)?.takeIf { it.isIntegralNumber && it.canConvertToLong() }?.longValue()

    fun instant(
        node: JsonNode,
        member: String,
    ): Instant? =
        try {
            text(node, member)?.let { OffsetDateTime.parse(it).toInstant() }
        } catch (_: DateTimeParseException) {
            null
        }
}

/** The generated `ApiClient` sends the request; the answer comes back raw, so the status precedes any parsing. */
internal class OwnerTransport(
    client: RestClient,
) : ApiClient(client) {
    fun send(config: RequestConfig<*>): ResponseEntity<String> {
        @Suppress("UNCHECKED_CAST")
        val typed = config as RequestConfig<Any>
        return prepare(defaults(typed)).retrieve().toEntity(String::class.java)
    }
}

/** The answer of the owner once it was read: its status and its raw body. */
internal class OwnerAnswer(
    val status: Int,
    val body: String?,
)

/**
 * The calls of one owning service through the policy of its edge. The status is read before the body (a 4xx with a
 * readable envelope is a [OwnerReply.Refused], any other failure of the owner or the edge is
 * [OwnerReply.Unavailable]); nothing here retries and nothing throws for an owner answer. The handler of [call] gets
 * only a 2xx answer.
 */
internal class OwnerEdge(
    private val service: OwningService,
    registry: ResilienceRegistry,
    private val clock: Clock,
    restClient: RestClient,
) {
    private val policy = OwningServiceEdge.policy(registry, service)
    private val transport = OwnerTransport(restClient)

    fun unreadable(status: Int): OwnerReply.Unavailable =
        OwnerReply.Unavailable("${service.edge}: unreadable answer (status $status)")

    fun <T> call(
        request: () -> RequestConfig<*>,
        onSuccess: (OwnerAnswer) -> OwnerReply<T>,
    ): OwnerReply<T> {
        // API-29: the deadline of the request being served; a call with none behind it gets the edge's timeout
        val deadline = CurrentDeadline.current() ?: Deadline.after(service.timeout, clock)
        return policy.execute(
            deadline,
            onCallNotPermitted = { OwnerReply.Unavailable("${service.edge}: call not permitted (breaker open)") },
            onConnectFailure = { OwnerReply.Unavailable("${service.edge}: connection refused") },
            onTimeout = { OwnerReply.Unavailable("${service.edge}: timed out") },
        ) {
            try {
                val response = transport.send(request())
                val status = response.statusCode.value()
                if (response.statusCode.is2xxSuccessful) {
                    onSuccess(OwnerAnswer(status, response.body))
                } else {
                    OwnerReply.Unavailable("${service.edge}: unexpected status $status")
                }
            } catch (failure: RestClientResponseException) {
                refusedOrUnavailable(failure)
            }
        }
    }

    private fun refusedOrUnavailable(failure: RestClientResponseException): OwnerReply<Nothing> {
        val status = failure.statusCode.value()
        val refusal =
            if (failure.statusCode.is4xxClientError) {
                OwnerEnvelope.refusal(status, failure.responseBodyAsString)
            } else {
                null
            }
        return if (refusal != null) {
            OwnerReply.Refused(refusal)
        } else {
            OwnerReply.Unavailable("${service.edge}: status $status")
        }
    }
}
