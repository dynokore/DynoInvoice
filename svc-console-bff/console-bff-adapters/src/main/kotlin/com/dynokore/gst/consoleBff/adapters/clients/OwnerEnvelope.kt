package com.dynokore.gst.consoleBff.adapters.clients

import com.dynokore.gst.consoleBff.application.override.OwnerReply
import com.dynokore.gst.consoleBff.application.override.OwnerRefusal
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.serviceruntime.resilience.ResilientEdge
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
            ?.let { runCatching { mapper.readTree(it) }.getOrElse { e -> if (e is JacksonException) null else throw e } }
            ?.takeIf { it.isObject }

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
    fun <B : Any> send(config: RequestConfig<B>): ResponseEntity<String> =
        prepare(defaults(config)).retrieve().toEntity(String::class.java)
}

/** The answer of the owner once it was read: its status and its raw body. */
internal class OwnerAnswer(
    val status: Int,
    val body: String?,
)

/**
 * One call through the policy of the edge. The status is read before the body (a 4xx with a readable envelope is a
 * [OwnerReply.Refused], any other failure of the owner or the edge is [OwnerReply.Unavailable]); nothing here retries
 * and nothing throws for an owner answer. [onSuccess] gets only a 2xx answer.
 */
internal fun <T> ownerCall(
    edge: String,
    timeout: java.time.Duration,
    policy: ResilientEdge,
    clock: Clock,
    transport: OwnerTransport,
    request: () -> RequestConfig<*>,
    onSuccess: (OwnerAnswer) -> OwnerReply<T>,
): OwnerReply<T> {
    // API-29: the deadline of the request being served; a call with none behind it gets the edge's timeout
    val deadline = CurrentDeadline.current() ?: Deadline.after(timeout, clock)
    return policy.execute(
        deadline,
        onCallNotPermitted = { OwnerReply.Unavailable("$edge: call not permitted (breaker open or bulkhead full)") },
        onConnectFailure = { OwnerReply.Unavailable("$edge: connection refused") },
        onTimeout = { OwnerReply.Unavailable("$edge: timed out") },
    ) {
        try {
            @Suppress("UNCHECKED_CAST")
            val response = transport.send(request() as RequestConfig<Any>)
            val status = response.statusCode.value()
            if (response.statusCode.is2xxSuccessful) {
                onSuccess(OwnerAnswer(status, response.body))
            } else {
                OwnerReply.Unavailable("$edge: unexpected status $status")
            }
        } catch (failure: RestClientResponseException) {
            val status = failure.statusCode.value()
            val refusal =
                if (failure.statusCode.is4xxClientError) OwnerEnvelope.refusal(status, failure.responseBodyAsString) else null
            if (refusal != null) OwnerReply.Refused(refusal) else OwnerReply.Unavailable("$edge: status $status")
        }
    }
}
