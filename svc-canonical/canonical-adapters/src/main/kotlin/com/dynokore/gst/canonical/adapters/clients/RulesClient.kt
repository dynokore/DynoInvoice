package com.dynokore.gst.canonical.adapters.clients

import com.dynokore.gst.canonical.adapters.clients.rules.api.EvaluationApi
import com.dynokore.gst.canonical.adapters.clients.rules.model.EvaluateDocumentRequest
import com.dynokore.gst.canonical.adapters.clients.rules.model.EvaluateDocumentRequestCounterpartySchemeData
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.serviceruntime.resilience.ResilienceRegistry
import com.dynokore.platform.serviceruntime.resilience.http.CurrentDeadline
import org.springframework.core.env.Environment
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import java.time.Clock
import java.time.Duration
import java.time.LocalDate

/** The sync edge canonical-svc -> rules-svc (sync-edges.yaml; `x-sync-edge` of `evaluateDocument`). */
object RulesEdge {
    const val EDGE = "canonical-svc->rules-svc"
    const val URL_PROPERTY = "canonical.rules-svc-url"

    /** `x-timeout` of `evaluateDocument`. */
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

/** The counterparty's scheme facts from the masterdata-svc resolution (FR-1.14, FR-1.15). */
data class SchemeData(
    val approvedSchemes: List<String>,
    val arcsGstChargeAgreed: Boolean? = null,
)

/** The inputs of one evaluation; the deprecated `election` is never part of them. */
data class EvaluationFacts(
    val entityRef: String,
    val transactionDate: LocalDate,
    val internalTaxCode: String,
    val electedSchemes: List<String>,
    val counterpartySchemeData: SchemeData?,
)

/** What rules-svc (or the policy of the edge) answered; no exception reaches the caller (ARC-19). */
sealed interface RulesAnswer {
    data class Verdict(
        val scopeVerdict: String,
        val documentTypeCode: String?,
    ) : RulesAnswer

    /** No answer: a 5xx, a timeout, an I/O error, an open breaker or a full bulkhead. The caller holds. */
    data object Unavailable : RulesAnswer

    /** A 4xx of rules-svc: a rules decision, not an outage. */
    data class Refused(
        val status: Int,
    ) : RulesAnswer
}

/**
 * TPL-07, ARC-08: the HTTP client of rules-svc for canonical-svc (`evaluateDocument`), over the client generated
 * from the owner's contract (STK-46) and the policy of the sync edge. Not a Spring component: the caller wires it.
 */
class RulesClient(
    builder: RestClient.Builder,
    private val registry: ResilienceRegistry,
    environment: Environment,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val api = EvaluationApi(builder.baseUrl(RulesEdge.baseUrl(environment)).build())

    fun evaluate(facts: EvaluationFacts): RulesAnswer {
        // API-29: the deadline of the request being served; a call with none behind it gets the contract's 300 ms
        val deadline = CurrentDeadline.current() ?: Deadline.after(RulesEdge.DEADLINE, clock)
        return registry.forEdge(RulesEdge.EDGE).execute(
            deadline,
            onCallNotPermitted = { RulesAnswer.Unavailable },
            onConnectFailure = { RulesAnswer.Unavailable },
            onTimeout = { RulesAnswer.Unavailable },
        ) {
            try {
                val body = api.evaluateDocumentWithHttpInfo(request(facts)).body
                if (body == null) {
                    RulesAnswer.Unavailable
                } else {
                    RulesAnswer.Verdict(body.scopeVerdict.value, body.documentTypeCode)
                }
            } catch (failure: RestClientResponseException) {
                if (failure.statusCode.is4xxClientError) {
                    RulesAnswer.Refused(failure.statusCode.value())
                } else {
                    RulesAnswer.Unavailable
                }
            } catch (_: RestClientException) {
                // an I/O error or an unreadable answer: no usable answer from the owner
                RulesAnswer.Unavailable
            }
        }
    }

    // `election` is deprecated and read only when electedSchemes is absent: it is never sent.
    private fun request(facts: EvaluationFacts) =
        EvaluateDocumentRequest(
            entityRef = facts.entityRef,
            transactionDate = facts.transactionDate,
            internalTaxCode = facts.internalTaxCode,
            electedSchemes = facts.electedSchemes.toSet(),
            counterpartySchemeData =
                facts.counterpartySchemeData?.let {
                    EvaluateDocumentRequestCounterpartySchemeData(
                        approvedSchemes = it.approvedSchemes,
                        arcsGstChargeAgreed = it.arcsGstChargeAgreed,
                    )
                },
        )
}
