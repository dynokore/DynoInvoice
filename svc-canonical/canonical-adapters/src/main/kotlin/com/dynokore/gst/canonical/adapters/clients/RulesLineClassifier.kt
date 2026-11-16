package com.dynokore.gst.canonical.adapters.clients

import com.dynokore.gst.canonical.adapters.clients.rules.api.EvaluationApi
import com.dynokore.gst.canonical.adapters.clients.rules.model.EvaluateDocumentRequest
import com.dynokore.gst.canonical.adapters.clients.rules.model.EvaluationResult
import com.dynokore.gst.canonical.application.aggregate.Classified
import com.dynokore.gst.canonical.application.aggregate.LineAnswer
import com.dynokore.gst.canonical.application.aggregate.LineClassifier
import com.dynokore.gst.canonical.application.aggregate.Refused
import com.dynokore.gst.canonical.application.aggregate.Unavailable
import com.dynokore.gst.canonical.domain.document.Direction
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.serviceruntime.resilience.ResilienceRegistry
import com.dynokore.platform.serviceruntime.resilience.http.CurrentDeadline
import org.springframework.core.env.Environment
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import java.time.Clock
import java.time.LocalDate

/**
 * [LineClassifier] over `evaluateDocument` of rules-svc for one internal tax code of an aggregate (INV-REQ-437), on the
 * edge and with the policy of [RulesClient]. The request carries the entity, the date, the code and the direction and
 * no `electedSchemes`. A 4xx or an answer without a category code is Refused; a 5xx, a timeout, an I/O error, an
 * open breaker, an empty body and two versions of one pack type are Unavailable. Not a Spring component.
 */
class RulesLineClassifier(
    builder: RestClient.Builder,
    private val registry: ResilienceRegistry,
    environment: Environment,
    private val clock: Clock = Clock.systemUTC(),
) : LineClassifier {
    private val api = EvaluationApi(builder.baseUrl(RulesEdge.baseUrl(environment)).build())

    override fun classify(
        entityRef: String,
        transactionDate: LocalDate,
        internalTaxCode: String,
        direction: Direction,
    ): LineAnswer {
        val deadline = CurrentDeadline.current() ?: Deadline.after(RulesEdge.DEADLINE, clock)
        return registry.forEdge(RulesEdge.EDGE).execute(
            deadline,
            onCallNotPermitted = { Unavailable },
            onConnectFailure = { Unavailable },
            onTimeout = { Unavailable },
        ) {
            try {
                val body =
                    api
                        .evaluateDocumentWithHttpInfo(
                            request(entityRef, transactionDate, internalTaxCode, direction),
                        ).body
                if (body == null) Unavailable else answerOf(body)
            } catch (failure: RestClientResponseException) {
                if (failure.statusCode.is4xxClientError) Refused else Unavailable
            } catch (_: RestClientException) {
                Unavailable
            }
        }
    }

    private fun answerOf(body: EvaluationResult): LineAnswer {
        val category = body.categoryCode
        val applied = body.packVersionsApplied.orEmpty()
        val versions =
            PACK_TYPES.associateWith { type ->
                applied.filter { it.packType == type }.map { it.version }.toSet()
            }
        return when {
            category == null -> {
                Refused
            }

            versions.values.any { it.size > 1 } -> {
                Unavailable
            }

            else -> {
                Classified(
                    categoryCode = category,
                    lineVerdict = body.scopeVerdict.value,
                    packVersions = versions.filterValues { it.isNotEmpty() }.mapValues { it.value.single() },
                    rate = body.rate,
                )
            }
        }
    }

    private fun request(
        entityRef: String,
        transactionDate: LocalDate,
        internalTaxCode: String,
        direction: Direction,
    ) = EvaluateDocumentRequest(
        entityRef = entityRef,
        transactionDate = transactionDate,
        internalTaxCode = internalTaxCode,
        direction = EvaluateDocumentRequest.Direction.valueOf(direction.name),
    )

    private companion object {
        val PACK_TYPES = listOf("SCOPE", "MAPPING", "MDE")
    }
}
