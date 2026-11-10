package com.dynokore.gst.rules.adapters.clients

import com.dynokore.gst.rules.adapters.clients.api.ConsentApi
import com.dynokore.gst.rules.adapters.clients.api.SeparationOfDutyApi
import com.dynokore.gst.rules.application.InstallationApproverFact
import com.dynokore.gst.rules.application.InstallationApproverFactPort
import com.dynokore.gst.rules.application.MappingAcknowledgement
import com.dynokore.gst.rules.application.MappingAcknowledgementPort
import com.dynokore.gst.rules.application.PackApprovalDecision
import com.dynokore.gst.rules.application.PackApprovalDecisionPort
import com.dynokore.gst.rules.application.PackApprovalRequest
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.serviceruntime.resilience.ResilienceRegistry
import com.dynokore.platform.serviceruntime.resilience.http.CurrentDeadline
import org.openapitools.client.infrastructure.ApiClient
import org.openapitools.client.infrastructure.RequestConfig
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration

/**
 * ARC-08, TPL-07: the three decision operations of access-svc that rules-svc asks (`decidePackApproval`,
 * `getInstallationApproverFact`, `getMappingAcknowledgement`) over the edge `rules-svc->access-svc`, through the client
 * generated from the owner's contract (STK-46), whose request configuration (method, path, query) is the one sent, and
 * the policy of the sync edge (500 ms, one retry, circuit breaker; edge row "Any -> access-svc (fallback)").
 *
 * Fails closed (NIST SP 800-162, OWASP ASVS V4.1): an error status, a timeout, no answer, an open breaker or a body that
 * does not match the contract is `Unavailable`, never an Allow. A 404 of the fact or the acknowledgement is
 * `NotRecorded` (the contract's "nothing recorded"). The body is read as a tree and checked member by member, so no
 * scalar is coerced ("false" is not a boolean) and no enum is matched in another case. This class is not wired; the
 * production defaults stay refusing until INV-T-10700.
 */
class AccessSvcClient(
    builder: RestClient.Builder,
    registry: ResilienceRegistry,
    private val clock: Clock,
    baseUrl: String,
) : PackApprovalDecisionPort,
    InstallationApproverFactPort,
    MappingAcknowledgementPort {
    private val restClient = builder.baseUrl(baseUrl).build()
    private val separation = SeparationOfDutyApi(restClient)
    private val consent = ConsentApi(restClient)
    private val transport = Transport(restClient)
    private val policy = registry.forEdge(EDGE)
    private val mapper = JsonMapper.builder().build()

    override fun decide(request: PackApprovalRequest): PackApprovalDecision =
        answer(
            failed = PackApprovalDecision.Unavailable,
            notFound = PackApprovalDecision.Unavailable, // the contract's decision is always 200
            config =
                separation.decidePackApprovalRequestConfig(
                    entityRef = request.entityRef,
                    packRef = request.packId,
                    packVersion = request.packVersion,
                    authorRef = request.authorRef,
                    approverRef = request.approverRef,
                    priorApproverRef = request.priorApproverRef.takeIf { it.isNotEmpty() },
                ),
            parse = ::decisionOf,
        )

    override fun read(): InstallationApproverFact =
        answer(
            failed = InstallationApproverFact.Unavailable,
            notFound = InstallationApproverFact.NotRecorded,
            config = separation.getInstallationApproverFactRequestConfig(),
            parse = ::factOf,
        )

    override fun read(
        packVersion: String,
        clientRef: String,
    ): MappingAcknowledgement =
        answer(
            failed = MappingAcknowledgement.Unavailable,
            notFound = MappingAcknowledgement.NotRecorded,
            config = consent.getMappingAcknowledgementRequestConfig(packVersion, clientRef),
            parse = ::acknowledgementOf,
        )

    /**
     * One call under the edge's policy. Only a 200 is read as an answer and only a 404 is [notFound]; any other status
     * throws out of the block, so the edge's retry sees it, and ends as [failed], as does a body that does not match
     * the contract, a timeout, an open breaker or no connection. The deadline is that of the request being served,
     * else the edge's contract timeout (API-29).
     */
    private fun <T> answer(
        failed: T,
        notFound: T,
        config: RequestConfig<Unit>,
        parse: (JsonNode) -> T,
    ): T {
        val deadline = CurrentDeadline.current() ?: Deadline.after(EDGE_TIMEOUT, clock)
        return try {
            policy.execute(
                deadline,
                onCallNotPermitted = { failed },
                onConnectFailure = { failed },
                onTimeout = { failed },
            ) {
                val response =
                    try {
                        transport.get(config)
                    } catch (status: RestClientResponseException) {
                        if (status.statusCode.value() == HttpStatus.NOT_FOUND.value()) return@execute notFound
                        throw status
                    }
                if (response.statusCode.value() != HttpStatus.OK.value()) {
                    failed
                } else {
                    try {
                        parse(tree(response.body))
                    } catch (_: MalformedBody) {
                        failed
                    }
                }
            }
        } catch (_: RuntimeException) {
            failed // an error status after the retry, or no answer whatever the transport reports: fail closed
        }
    }

    private fun tree(body: String?): JsonNode =
        try {
            mapper.readTree(body ?: throw MalformedBody())
        } catch (_: RuntimeException) {
            throw MalformedBody()
        }

    private fun decisionOf(node: JsonNode): PackApprovalDecision {
        val soleOperator = node.requiredBoolean("soleOperator")
        val outstanding = node.requiredStringArray("outstanding").toSet()
        val reasonCode = node.optionalString("reasonCode")
        val option = node.optionalString("option")
        val ackRef = node.optionalString("soleOperatorAckRef")
        return when (node.requiredString("decision")) {
            "ALLOW" -> PackApprovalDecision.Allow(soleOperator, ackRef, option ?: throw MalformedBody(), outstanding)
            "DENY" -> PackApprovalDecision.Deny(reasonCode ?: throw MalformedBody())
            else -> throw MalformedBody()
        }
    }

    private fun factOf(node: JsonNode): InstallationApproverFact {
        node.requiredString("recordedAt")
        return InstallationApproverFact.Recorded(node.requiredBoolean("distinctTaxApprover"))
    }

    private fun acknowledgementOf(node: JsonNode): MappingAcknowledgement {
        node.requiredString("packVersion")
        node.requiredString("clientRef")
        node.requiredString("acknowledgedAt")
        return MappingAcknowledgement.Recorded
    }

    /** A body that does not match the contract. */
    private class MalformedBody : RuntimeException()

    private fun JsonNode.requiredString(name: String): String =
        get(name)?.takeIf { it.isString }?.asString() ?: throw MalformedBody()

    private fun JsonNode.optionalString(name: String): String? =
        if (has(name)) requiredString(name) else null

    private fun JsonNode.requiredBoolean(name: String): Boolean =
        get(name)?.takeIf { it.isBoolean }?.asBoolean() ?: throw MalformedBody()

    private fun JsonNode.requiredStringArray(name: String): List<String> {
        val array = get(name)?.takeIf { it.isArray } ?: throw MalformedBody()
        val values = mutableListOf<String>()
        for (element in array) values += if (element.isString) element.asString() else throw MalformedBody()
        return values
    }

    /** The generated `ApiClient` sends the request; the answer comes back raw, so the status precedes any parsing. */
    private class Transport(
        client: RestClient,
    ) : ApiClient(client) {
        fun get(config: RequestConfig<Unit>): ResponseEntity<String> =
            prepare(defaults(config)).retrieve().toEntity(String::class.java)
    }

    private companion object {
        const val EDGE = "rules-svc->access-svc"

        /** `x-timeout` of the three operations in the access contract. */
        val EDGE_TIMEOUT: Duration = Duration.ofMillis(500)
    }
}
