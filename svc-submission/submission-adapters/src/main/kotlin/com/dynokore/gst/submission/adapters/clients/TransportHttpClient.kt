package com.dynokore.gst.submission.adapters.clients

import com.dynokore.gst.submission.adapters.clients.transport.api.DespatchApi
import com.dynokore.gst.submission.adapters.clients.transport.model.SubmitPayloadRequest
import com.dynokore.gst.submission.adapters.clients.transport.model.TransportOutcome
import com.dynokore.gst.submission.application.queue.TransportClient
import com.dynokore.gst.submission.application.queue.TransportRequest
import com.dynokore.gst.submission.application.queue.TransportResult
import com.dynokore.gst.submission.domain.transitions.RejectionReason
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.env.Environment
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpRequest
import org.springframework.http.HttpStatus
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpResponse
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.io.ByteArrayInputStream
import java.net.http.HttpClient
import java.time.Duration

/**
 * INV-REQ-1010: the production adapter of the [TransportClient] port. It sends the payload of one attempt to
 * transport-svc through the client generated from its contract (STK-46, ARC-08), operation `submitPayload`, with the
 * `Idempotency-Key` of the attempt (the correlation id, INV-Q-14328 A). One call sends one request: the retry is
 * owned by the retry planner. Fail closed: only a 202 answer that reads as ACCEPTED with an acknowledgement or as
 * REJECTED with at least one reason is a final outcome; every other answer, a timeout and any I/O error is a
 * [TransportResult.TransientFailure] of the catalogue code [TRANSIENT_CODE], never a thrown exception. A null or
 * empty payload is a programming error and fails before any request. The credentials come from the
 * `internalRestClientBuilder` interceptors. The bean exists only when `inv.transport-client.base-url` is set.
 */
@Component
@ConditionalOnProperty(prefix = "inv.transport-client", name = ["base-url"])
class TransportHttpClient(
    @Qualifier("internalRestClientBuilder") builder: RestClient.Builder,
    environment: Environment,
) : TransportClient {
    private val answered = ThreadLocal<ByteArray>()
    private val api: DespatchApi

    init {
        val readTimeout = Duration.ofMillis(environment.getRequiredProperty(TIMEOUT_PROPERTY, Long::class.java))
        api =
            DespatchApi(
                builder
                    .baseUrl(environment.getRequiredProperty(URL_PROPERTY))
                    .requestFactory(
                        JdkClientHttpRequestFactory(
                            HttpClient
                                .newBuilder()
                                .version(HttpClient.Version.HTTP_1_1)
                                .connectTimeout(readTimeout)
                                .build(),
                        ).apply { setReadTimeout(readTimeout) },
                    ).requestInterceptor(::keepAnsweredBytes)
                    .build(),
            )
    }

    /** Keeps the bytes of the answered body for [TransportResult.responseBytes] and hands a replayable body on. */
    private fun keepAnsweredBytes(
        request: HttpRequest,
        body: ByteArray,
        execution: ClientHttpRequestExecution,
    ): ClientHttpResponse {
        val response = execution.execute(request, body)
        val bytes = response.body.readAllBytes()
        answered.set(bytes)
        return object : ClientHttpResponse by response {
            override fun getBody() = ByteArrayInputStream(bytes)
        }
    }

    override fun submit(request: TransportRequest): TransportResult {
        val payload = request.payload
        require(payload != null && payload.isNotEmpty()) { "the payload of a transport request must not be empty" }
        answered.remove()
        return try {
            val response =
                api.submitPayloadWithHttpInfo(
                    request.idempotencyKey,
                    SubmitPayloadRequest(
                        docUuid = java.util.UUID.fromString(request.docUuid),
                        version = request.version,
                        payload = payload,
                        entityRef = request.entityRef,
                    ),
                )
            val bytes = answered.get() ?: ByteArray(0)
            val outcome = response.body
            if (response.statusCode.value() == HttpStatus.ACCEPTED.value() && outcome != null) {
                map(outcome, bytes)
            } else {
                transient(bytes)
            }
        } catch (_: RuntimeException) {
            // a status other than 2xx, an unreadable body, a timeout or an I/O error: the transport is not known to
            // have decided, so the attempt is transient and the retry planner bounds it
            transient(answered.get() ?: ByteArray(0))
        } finally {
            answered.remove()
        }
    }

    private fun map(
        outcome: TransportOutcome,
        bytes: ByteArray,
    ): TransportResult {
        val acknowledgement = outcome.acknowledgement
        val reasons = outcome.reasons.orEmpty()
        return when {
            outcome.outcome == TransportOutcome.Outcome.ACCEPTED && acknowledgement != null -> {
                TransportResult.Accepted(acknowledgement.reference, acknowledgement.receivedAt.toInstant(), bytes)
            }

            outcome.outcome == TransportOutcome.Outcome.REJECTED && reasons.isNotEmpty() -> {
                TransportResult.Rejected(reasons.map { RejectionReason(it.code, it.field, it.message) }, bytes)
            }

            else -> {
                transient(bytes)
            }
        }
    }

    private fun transient(bytes: ByteArray): TransportResult = TransportResult.TransientFailure(TRANSIENT_CODE, bytes)

    private companion object {
        const val URL_PROPERTY = "inv.transport-client.base-url"
        const val TIMEOUT_PROPERTY = "inv.transport-client.read-timeout-ms"

        /** Catalogue code of a transport timeout or connection failure (disposition AUTO_RETRY). */
        const val TRANSIENT_CODE = "SUB-001"
    }
}
