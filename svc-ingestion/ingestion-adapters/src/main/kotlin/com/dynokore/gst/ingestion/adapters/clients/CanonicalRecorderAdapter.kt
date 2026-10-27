package com.dynokore.gst.ingestion.adapters.clients

import com.dynokore.gst.ingestion.adapters.clients.api.DocumentsApi
import com.dynokore.gst.ingestion.adapters.clients.model.ErrorEnvelope
import com.dynokore.gst.ingestion.adapters.clients.model.RecordDocumentRequest
import com.dynokore.gst.ingestion.adapters.clients.model.RecordDocumentResponse
import com.dynokore.gst.ingestion.application.recording.CanonicalRecorder
import com.dynokore.gst.ingestion.application.recording.RecordDocumentCommand
import com.dynokore.gst.ingestion.domain.recording.CanonicalAnswer
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.serviceruntime.resilience.ResilienceRegistry
import com.dynokore.platform.serviceruntime.resilience.http.CurrentDeadline
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import tools.jackson.module.kotlin.readValue
import java.time.Clock
import java.time.Duration

/** The edge `ingestion-svc->canonical-svc` could not answer: timed out, breaker or bulkhead refused it, or no connection. */
class CanonicalUnavailableException(
    message: String,
) : RuntimeException(message)

/**
 * TPL-07, ARC-08: the `recordDocument` operation of canonical-svc (INV-REQ-806, INV-REQ-1192) over HTTP, through the
 * client generated from the owner's contract (STK-46) and the policy of the sync edge. A replay is canonical-svc's
 * decision and is relayed unchanged; this adapter keeps no record of it.
 */
@Component
class CanonicalRecorderAdapter(
    @Qualifier("internalRestClientBuilder") builder: RestClient.Builder,
    registry: ResilienceRegistry,
    private val clock: Clock,
    @Value($$"${ingestion.canonical-svc-url}") baseUrl: String,
) : CanonicalRecorder {
    init {
        require(baseUrl.isNotBlank()) { "ingestion.canonical-svc-url must not be blank" }
    }

    private val api = DocumentsApi(builder.baseUrl(baseUrl).build())
    private val policy = registry.forEdge(EDGE)
    private val mapper = JsonMapper
            .builder()
            .addModule(KotlinModule.Builder().build())
            .build()

    override fun record(command: RecordDocumentCommand): CanonicalAnswer {
        val request = mapper.readValue<RecordDocumentRequest>(command.body)
        // the generated types tolerate unknown members (API-09): the request must still carry the whole body, no more
        require(mapper.valueToTree<JsonNode>(request).withoutNulls() == mapper.readTree(command.body).withoutNulls()) {
            "the generated RecordDocumentRequest cannot carry the body of ${command.sourceKey} revision ${command.sourceRevision} unchanged"
        }
        // API-29: the deadline of the request being served; a call with none behind it gets the edge's contract timeout
        val deadline = CurrentDeadline.current() ?: Deadline.after(EDGE_TIMEOUT, clock)
        return policy.execute(
            deadline,
            onCallNotPermitted = { throw CanonicalUnavailableException("$EDGE: call not permitted (breaker open or bulkhead full)") },
            onConnectFailure = { throw CanonicalUnavailableException("$EDGE: connection refused") },
            onTimeout = { throw CanonicalUnavailableException("$EDGE: timed out") },
        ) {
            send(request)
        }
    }

    private fun JsonNode.withoutNulls(): JsonNode =
        when {
            isObject -> mapper.createObjectNode().also { out -> properties().forEach { (k, v) -> if (!v.isNull) out.set(k, v.withoutNulls()) } }
            isArray -> mapper.createArrayNode().also { out -> forEach { out.add(it.withoutNulls()) } }
            else -> this
        }

    private fun send(request: RecordDocumentRequest): CanonicalAnswer =
        try {
            val response = api.recordDocumentWithHttpInfo(request)
            answer(response.statusCode.value(), response.body)
        } catch (failure: RestClientResponseException) {
            rejected(failure)
        }

    private fun answer(
        status: Int,
        body: RecordDocumentResponse?,
    ): CanonicalAnswer =
        when {
            body == null -> CanonicalAnswer.Rejected(null, status)
            status == HttpStatus.CREATED.value() -> CanonicalAnswer.Created(body.docUuid, body.version.toLong())
            status == HttpStatus.OK.value() -> CanonicalAnswer.Replay(body.docUuid, body.version.toLong())
            status == HttpStatus.ACCEPTED.value() ->
                CanonicalAnswer.Held(body.docUuid, body.version.toLong(), body.reasons.orEmpty().map { it.code })
            else -> CanonicalAnswer.Rejected(null, status)
        }

    /** A 4xx with the Error envelope carries its code and status; a 5xx or any other body carries only the status. */
    private fun rejected(failure: RestClientResponseException): CanonicalAnswer {
        val status = failure.statusCode.value()
        if (failure.statusCode.is4xxClientError) {
            val envelope = runCatching { mapper.readValue<ErrorEnvelope>(failure.responseBodyAsByteArray) }.getOrNull()
            if (envelope != null) return CanonicalAnswer.Rejected(envelope.code, envelope.status)
        }
        return CanonicalAnswer.Rejected(null, status)
    }

    private companion object {
        const val EDGE = "ingestion-svc->canonical-svc"

        /** `x-timeout` of `recordDocument` in the canonical contract. */
        val EDGE_TIMEOUT: Duration = Duration.ofMillis(2000)
    }
}
