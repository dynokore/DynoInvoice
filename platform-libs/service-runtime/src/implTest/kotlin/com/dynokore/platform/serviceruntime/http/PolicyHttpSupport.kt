package com.dynokore.platform.serviceruntime.http

import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.contract.ContractValidator
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.policy.PolicyRoleResolver
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** A test run on PostgreSQL 17 and on 18 (the same set the job command tests use). */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ParameterizedTest(name = "{displayName}[{0}]")
@ValueSource(strings = ["postgres:17", "postgres:18"])
annotation class OnPostgres

/** The two operations, each with the field that makes its request body complete. */
enum class PolicyOp(
    val suffix: String,
) {
    SET(""),
    ACK("/relaxations"),
    ;

    fun path(variationPoint: String) = "/internal/v1/policies/$variationPoint$suffix"
}

/**
 * HTTP-level support of the policy command tests: one [PolicyHttpHarness] per image, a [Service] per test with its own
 * tenant, tokens, role port and recording writer, requests posted to the real [PolicyCommandServlet], and responses
 * checked against the pinned contract (`service-runtime/openapi.yaml`) and the pinned error envelope.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
open class PolicyHttpBase {
    private val harnesses = ConcurrentHashMap<String, PolicyHttpHarness>()
    protected val mapper = ObjectMapper()
    protected val contracts = ContractValidator()
    private val openapi = "service-runtime/openapi.yaml"
    private val envelope = "service-runtime/schemas/error-envelope.schema.json"

    @AfterAll
    fun closeHarnesses() = harnesses.values.forEach { it.close() }

    protected fun harness(image: String) = harnesses.computeIfAbsent(image) { PolicyHttpHarness(it) }

    protected inner class Service(
        image: String,
        bindRoles: Boolean = true,
    ) {
        val h = harness(image)
        val tenant = TenantId(UUID.randomUUID())
        val writer = RecordingWriter(h.real)

        /** Roles per actor the fake port answers; a missing actor gets the empty set. */
        val roles = ConcurrentHashMap<String, Set<String>>()

        /** What the fake port does instead of answering from [roles]. */
        @Volatile var portFailure: (() -> Nothing)? = null
        val portCalls = CopyOnWriteArrayList<List<Any?>>()
        private val tokens = ConcurrentHashMap<String, EndUserPrincipal>()
        private val resolver =
            EndUserPrincipalResolver { header ->
                header?.removePrefix("Bearer ")?.let { tokens[it] }
            }
        private val port =
            PolicyRoleResolver { actor, tenant, level, scopeRef, entityRef ->
                portCalls += listOf(actor, tenant, level, scopeRef, entityRef)
                portFailure?.invoke()
                roles[actor].orEmpty()
            }
        val idempotency = IdempotencyStore(h.scope, h.dsl, PolicyHttpHarness.SCHEMA)
        val servlet =
            PolicyCommandServlet(writer, resolver, h.scope, idempotency, port.takeIf { bindRoles })

        /** Registers `Bearer <name>` for a tenant user holding [entities] (current set unless [current] is false). */
        fun user(
            name: String,
            vararg entities: String,
            current: Boolean = true,
            roleSet: Set<String> = emptySet(),
        ): String {
            tokens[name] = EndUserPrincipal(name, tenant, entities.map { EntityRef(it) }.toSet(), current)
            roles[name] = roleSet
            return "Bearer $name"
        }

        /** Registers `Bearer <name>` for a platform operator: a token without a tenant. */
        fun operator(
            name: String,
            roleSet: Set<String>,
        ): String {
            tokens[name] = EndUserPrincipal(name, null, emptySet())
            roles[name] = roleSet
            return "Bearer $name"
        }

        fun post(
            path: String,
            body: String?,
            token: String? = null,
            key: String? = UUID.randomUUID().toString(),
            method: String = "POST",
        ): Answer {
            val request = MockHttpServletRequest(method, path)
            token?.let { request.addHeader("Authorization", it) }
            key?.let { request.addHeader("Idempotency-Key", it) }
            body?.let {
                request.contentType = "application/json"
                request.setContent(it.toByteArray())
            }
            val response = MockHttpServletResponse()
            servlet.service(request, response)
            return Answer(
                path,
                response.status,
                response.contentType.orEmpty(),
                mapper.readTree(response.contentAsString),
                body,
                key,
            )
        }

        fun stored(): Long = h.settings(tenant.value)
    }

    protected inner class Answer(
        val path: String,
        val status: Int,
        val contentType: String,
        val body: JsonNode,
        private val requestBody: String?,
        private val key: String?,
    ) {
        private val operation get() = contracts.operation(openapi, "POST", path)

        /** The response matches the contract (a request meant to be invalid is not checked against it). */
        fun responseConforms(): Answer {
            operation.responseErrors(status, contentType, body).shouldBeEmpty()
            return this
        }

        /** The request was valid and the response matches the contract. */
        fun conforms(): Answer {
            val headers = buildMap { key?.let { put("idempotency-key", it) } }
            operation.requestErrors(headers, mapper.readTree(requestBody ?: "{}")).shouldBeEmpty()
            return responseConforms()
        }

        /** 200 and a body of the contract. */
        fun ok(): Answer {
            status shouldBe 200
            return conforms()
        }

        /** A closed service-runtime case (ARC-19) at its SRT code and status. */
        fun closed(
            expected: ClosedErrorCode,
            declared: Boolean = true,
        ): Answer {
            status shouldBe expected.httpStatus
            contentType.substringBefore(';') shouldBe Problems.CONTENT_TYPE
            body.get("code").asString() shouldBe expected.code
            body.get("type").asString() shouldBe "https://errors.invoice.internal/${expected.code}"
            body.get("severity").asString() shouldBe "BLOCK"
            body.get("disposition").asString() shouldBe expected.disposition
            body.get("remediationOwner").asString() shouldBe "Platform"
            body.get("status").asInt() shouldBe expected.httpStatus
            if (expected == ClosedErrorCode.INTERNAL_ERROR) body.has("detail") shouldBe false
            contracts.schemaErrors(envelope, body).shouldBeEmpty()
            // a path the contract does not declare has no operation to check the response against
            return if (declared) responseConforms() else this
        }

        /** A POL refusal at the status, title and owner of its catalogue row (API-20, API-21). */
        fun refused(expected: PolicyErrorCode): Answer {
            status shouldBe expected.httpStatus
            contentType.substringBefore(';') shouldBe Problems.CONTENT_TYPE
            body.get("code").asString() shouldBe expected.code
            body.get("type").asString() shouldBe "https://errors.invoice.internal/${expected.code}"
            body.get("title").asString() shouldBe expected.title
            body.get("severity").asString() shouldBe "BLOCK"
            body.get("disposition").asString() shouldBe "TERMINAL"
            body.get("remediationOwner").asString() shouldBe expected.owner.text
            contracts.schemaErrors(envelope, body).shouldBeEmpty()
            return responseConforms()
        }
    }

    /** A request body of [op]: the scope, a selected option, an effective date and the operation's own fields. */
    protected fun request(
        op: PolicyOp,
        level: ScopeLevel,
        scopeRef: String? = null,
        entityRef: String? = null,
        selection: RequestFields = RequestFields(),
    ): String {
        val fields = mutableListOf("\"scopeLevel\":\"${level.name}\"")
        scopeRef?.let { fields += "\"scopeRef\":\"$it\"" }
        entityRef?.let { fields += "\"entityRef\":\"$it\"" }
        val value = selection.value?.let { ",\"value\":$it" }.orEmpty()
        fields += "\"selected\":[{\"option\":\"${selection.option}\"$value}]"
        fields += "\"effectiveDate\":\"${selection.effectiveDate}\""
        when (op) {
            PolicyOp.SET -> selection.reason?.let { fields += "\"reason\":\"$it\"" }
            PolicyOp.ACK -> fields += "\"asRole\":\"${selection.asRole}\""
        }
        if (selection.extra.isNotEmpty()) fields += selection.extra
        return "{" + fields.joinToString(",") + "}"
    }

    /** The selected option and the optional fields of a request body made by [request]. */
    protected data class RequestFields(
        val option: String = "B",
        val value: String? = null,
        val effectiveDate: String = NEXT_MONTH,
        val reason: String? = "because",
        val asRole: String = "tenant.admin",
        val extra: String = "",
    )

    protected companion object {
        const val NEXT_MONTH = "2026-11-01"
        const val TODAY = "2026-10-01"
    }
}
