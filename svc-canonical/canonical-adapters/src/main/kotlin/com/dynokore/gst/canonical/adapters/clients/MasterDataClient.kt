package com.dynokore.gst.canonical.adapters.clients

import com.dynokore.gst.canonical.adapters.clients.masterdata.api.ResolutionApi
import com.dynokore.gst.canonical.adapters.clients.masterdata.model.Counterparty
import com.dynokore.gst.canonical.adapters.clients.masterdata.model.ErrorEnvelope
import com.dynokore.gst.canonical.adapters.clients.masterdata.model.IngestedCounterpartyRequest
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.serviceruntime.resilience.ResilienceRegistry
import com.dynokore.platform.serviceruntime.resilience.http.CurrentDeadline
import org.springframework.core.env.Environment
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import tools.jackson.module.kotlin.readValue
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

/** The sync edge canonical-svc -> masterdata-svc (sync-edges.yaml; `x-sync-edge` of both operations used here). */
object MasterDataEdge {
    const val EDGE = "canonical-svc->masterdata-svc"
    const val URL_PROPERTY = "canonical.masterdata-svc-url"

    /** `x-timeout` of `resolveCounterparty` and `recordIngestedCounterparty`; `timeout_or_budget` of the edge row. */
    val DEADLINE: Duration = Duration.ofMillis(300)

    /** Required, not blank, not an unresolved placeholder, no default (TPL-16, twelve-factor config). */
    fun baseUrl(environment: Environment): String {
        val value =
            try {
                environment.getProperty(URL_PROPERTY)
            } catch (failure: IllegalArgumentException) {
                throw IllegalArgumentException("$URL_PROPERTY has an unresolvable placeholder", failure)
            }
        requireNotNull(value) { "$URL_PROPERTY is required and has no default" }
        require(value.isNotBlank()) { "$URL_PROPERTY must not be blank" }
        require(!value.trim().startsWith("\${")) { "$URL_PROPERTY is an unresolved placeholder: $value" }
        return value
    }
}

/** What masterdata-svc (or the policy of the edge) answered; no exception reaches the caller (ARC-19). */
sealed interface MasterDataAnswer<out T> {
    data class Answered<T>(
        val value: T,
    ) : MasterDataAnswer<T>

    /** A 4xx of the owner: its status and the `code` of its envelope, relayed unchanged (null when none). */
    data class Refused(
        val status: Int,
        val code: String?,
    ) : MasterDataAnswer<Nothing>

    /** No answer: a 5xx, a timeout, an I/O error, an open breaker or a full bulkhead. */
    data class Unavailable(
        val reason: String,
    ) : MasterDataAnswer<Nothing>
}

/** The counterparty the resolution found; `partyRef` is null when the owner answered without a counterparty. */
data class ResolvedCounterparty(
    val partyRef: String?,
)

/**
 * TPL-07, ARC-08: the HTTP client of masterdata-svc for canonical-svc, over the client generated from the owner's
 * contract (STK-46) and the policy of the sync edge. Not a Spring component: the caller task wires it.
 */
class MasterDataClient(
    builder: RestClient.Builder,
    environment: Environment,
    private val registry: ResilienceRegistry,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val api = ResolutionApi(builder.baseUrl(MasterDataEdge.baseUrl(environment)).build())
    private val mapper =
        JsonMapper
            .builder()
            .addModule(KotlinModule.Builder().build())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build()

    fun resolveCounterparty(
        entityRef: String,
        constitution: String,
        partyScheme: String,
        partyValue: String,
        transactionDate: LocalDate,
    ): MasterDataAnswer<ResolvedCounterparty> =
        call {
            val body =
                api
                    .resolveCounterpartyWithHttpInfo(
                        entityRef = entityRef,
                        constitution = constitution,
                        transactionDate = transactionDate,
                        partyScheme = partyScheme,
                        partyValue = partyValue,
                    ).body
            if (body == null) {
                MasterDataAnswer.Unavailable("${MasterDataEdge.EDGE}: empty body")
            } else {
                MasterDataAnswer.Answered(ResolvedCounterparty(body.counterparty?.partyRef))
            }
        }

    fun recordIngestedCounterparty(
        counterpartyRef: String,
        idempotencyKey: UUID,
        request: IngestedCounterpartyRequest,
    ): MasterDataAnswer<Counterparty> =
        call {
            val body =
                api
                    .recordIngestedCounterpartyWithHttpInfo(
                        counterpartyRef = counterpartyRef,
                        idempotencyKey = idempotencyKey,
                        ingestedCounterpartyRequest = request,
                    ).body
            if (body == null) {
                MasterDataAnswer.Unavailable("${MasterDataEdge.EDGE}: empty body")
            } else {
                MasterDataAnswer.Answered(body)
            }
        }

    private fun <T> call(send: () -> MasterDataAnswer<T>): MasterDataAnswer<T> {
        // the registry is asked once per call, for this one edge; API-29: the deadline of the request being
        // served; a call with none behind it gets the contract's 300 ms
        val deadline = CurrentDeadline.current() ?: Deadline.after(MasterDataEdge.DEADLINE, clock)
        return registry.forEdge(MasterDataEdge.EDGE).execute(
            deadline,
            onCallNotPermitted = { unavailable("call not permitted (breaker open or bulkhead full)") },
            onConnectFailure = { unavailable("connection refused") },
            onTimeout = { unavailable("timed out") },
        ) {
            try {
                send()
            } catch (failure: RestClientResponseException) {
                refusal(failure)
            } catch (failure: RestClientException) {
                // an I/O error or an unreadable answer: no usable answer from the owner
                unavailable(failure.javaClass.simpleName)
            }
        }
    }

    private fun unavailable(reason: String): MasterDataAnswer<Nothing> =
        MasterDataAnswer.Unavailable("${MasterDataEdge.EDGE}: $reason")

    /** A 4xx is the owner's refusal, relayed with its code; a 5xx (or any other status) is no answer. */
    private fun refusal(failure: RestClientResponseException): MasterDataAnswer<Nothing> {
        val status = failure.statusCode.value()
        if (!failure.statusCode.is4xxClientError) return unavailable("status $status")
        val code =
            runCatching { mapper.readValue<ErrorEnvelope>(failure.responseBodyAsByteArray).code }.getOrNull()
        return MasterDataAnswer.Refused(status, code)
    }
}
