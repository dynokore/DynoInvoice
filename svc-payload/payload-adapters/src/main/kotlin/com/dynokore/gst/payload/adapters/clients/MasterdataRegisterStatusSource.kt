package com.dynokore.gst.payload.adapters.clients

import com.dynokore.gst.payload.adapters.clients.masterdata.api.RegisterAndRatesApi
import com.dynokore.gst.payload.application.preissuance.RegisterLookup
import com.dynokore.gst.payload.application.preissuance.RegisterQuery
import com.dynokore.gst.payload.application.preissuance.RegisterStatusSource
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
import java.time.LocalDate

/**
 * INV-REQ-475, INV-REQ-477, ARC-08: asks `getRegisterAndRates` of masterdata-svc over HTTP, through the client
 * generated from the owner's contract (STK-46) and the policy of the sync edge. The Supplier GSTN goes unchanged as
 * `counterpartyUen`. Fail soft: a 4xx, a 5xx after the edge's retry, a timeout, an open breaker, no connection and an
 * unreadable body are each [RegisterLookup.Unavailable], never an exception.
 *
 * Not a scanned component: the bean and its property `payload.masterdata-svc-url` belong to the wiring slice.
 */
class MasterdataRegisterStatusSource(
    @Qualifier("internalRestClientBuilder") builder: RestClient.Builder,
    registry: ResilienceRegistry,
    private val clock: Clock,
    environment: Environment,
) : RegisterStatusSource {
    private val restClient = builder.baseUrl(baseUrl(environment)).build()
    private val api = RegisterAndRatesApi(restClient)
    private val transport = Transport(restClient)
    private val mapper = JsonMapper.builder().build()
    private val policy = registry.forEdge(EDGE)

    override fun lookup(query: RegisterQuery): RegisterLookup {
        // API-29: the deadline of the request being served; a call with none behind it gets the edge's contract timeout
        val deadline = CurrentDeadline.current() ?: Deadline.after(EDGE_TIMEOUT, clock)
        return try {
            policy.execute(
                deadline,
                onCallNotPermitted = { RegisterLookup.Unavailable },
                onConnectFailure = { RegisterLookup.Unavailable },
                onTimeout = { RegisterLookup.Unavailable },
            ) {
                ask(query)
            }
        } catch (_: Exception) {
            // The surfaced 5xx (after the edge's retry) and any other failure: fail soft, never an exception
            RegisterLookup.Unavailable
        }
    }

    private fun ask(query: RegisterQuery): RegisterLookup {
        val config =
            api.getRegisterAndRatesRequestConfig(
                counterpartyUen = query.supplierGstn,
                currency = query.currency,
                transactionDate = query.invoiceDate,
                entityRef = query.entityRef,
            )
        val raw =
            try {
                transport.get(config).body
            } catch (failure: RestClientResponseException) {
                // a 5xx surfaces (the breaker records it, the edge retries it); a 4xx is answered inside the block
                if (!failure.statusCode.is4xxClientError) throw failure
                // the status and the operation only, never the identifier or the body
                log.warn("getRegisterAndRates answered HTTP {}", failure.statusCode.value())
                return RegisterLookup.Unavailable
            }
        val register = readTree(raw)?.get("register")?.takeIf { it.isObject }
        val status = register?.get("status")?.takeIf { it.isString }?.asString()
        return if (status == null) {
            RegisterLookup.Unavailable
        } else {
            RegisterLookup.Answered(status, asOf(register))
        }
    }

    private fun asOf(register: JsonNode): LocalDate? =
        register.get("asOf")?.takeIf { it.isString }?.let { runCatching { LocalDate.parse(it.asString()) }.getOrNull() }

    private fun readTree(raw: String?): JsonNode? =
        raw?.takeIf { it.isNotBlank() }?.let { runCatching { mapper.readTree(it) }.getOrNull() }?.takeIf { it.isObject }

    /** The generated `ApiClient` sends the request; the answer comes back raw, so nothing is decoded leniently. */
    private class Transport(
        client: RestClient,
    ) : ApiClient(client) {
        fun get(config: RequestConfig<Unit>): ResponseEntity<String> =
            prepare(defaults(config)).retrieve().toEntity(String::class.java)
    }

    companion object {
        private val log = LoggerFactory.getLogger(MasterdataRegisterStatusSource::class.java)

        private const val URL_PROPERTY = "payload.masterdata-svc-url"
        const val EDGE = "payload-svc->masterdata-svc"

        /** `x-timeout` of `getRegisterAndRates` in the masterdata contract: 1000 ms. */
        val EDGE_TIMEOUT: Duration = Duration.ofMillis(1000)

        private fun baseUrl(environment: Environment): String =
            environment.getRequiredProperty(URL_PROPERTY).also {
                require(it.isNotBlank()) { "$URL_PROPERTY must not be blank" }
            }
    }
}
