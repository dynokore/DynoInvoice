package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.http.ApiRefusal
import com.dynokore.gst.access.adapters.http.ApprovalOperations
import com.dynokore.gst.access.adapters.http.GrantsController
import com.dynokore.gst.access.adapters.http.SoleOperatorController
import com.dynokore.gst.access.contract.model.GrantRequest
import com.dynokore.gst.access.contract.model.OverrideReleaseDecision
import com.dynokore.gst.access.contract.model.PackApprovalDecision
import com.dynokore.gst.access.contract.model.SoleOperatorAckRequest
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.configresolver.BusinessDate
import com.dynokore.platform.serviceruntime.http.PolicyCommandServlet
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.replica.OrganisationStructureChanged
import com.dynokore.platform.serviceruntime.replica.StructureChange
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import jakarta.servlet.http.HttpServlet
import org.assertj.core.api.Assertions.assertThat
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import org.springframework.boot.web.servlet.ServletRegistrationBean
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * Any bearer token `tok:<actor>` is that actor in [tenant], with a stale entity set so that the roles of the actor at
 * the scope come from this service's own grants (INV-Q-5235): the test principal source of the policy path.
 */
internal class ActorTokens(
    private val tenant: TenantId,
) : EndUserPrincipalResolver {
    override fun resolve(authorization: String?): EndUserPrincipal? =
        authorization
            ?.takeIf { it.startsWith("Bearer tok:") }
            ?.removePrefix("Bearer tok:")
            ?.let { EndUserPrincipal(it, tenant, emptySet(), entitySetCurrent = false) }
}

/**
 * One tenant over the real context and database, as the approval tests use it: the first `tenant.admin`, the structure
 * (ENT-1, ENT-2 and the client CLI-1), the controllers called under a bound tenant, and the mounted policy servlet.
 */
internal class ApprovalEnv(
    val context: AssertableWebApplicationContext,
    val tenant: TenantId,
) {
    val tenants = context.getBean(TenantContext::class.java)
    val operations = context.getBean(ApprovalOperations::class.java)
    val grants = context.getBean(GrantsController::class.java)
    val soleOperator = context.getBean(SoleOperatorController::class.java)
    val clock = context.getBean(Clock::class.java) as MovableClock
    val consents = ConsentSwitch(context, tenant)
    private val mapper = ObjectMapper()

    init {
        // a fixture: no production path creates the first tenant.admin (INV-Q-8185)
        Seeds.grant(context, tenant, SeedSpec(ADMIN, Role.TENANT_ADMIN))
        listOf("ENT-1", "ENT-2", "CLI-1").forEach {
            context
                .getBean(StructureReplica::class.java)
                .accept(OrganisationStructureChanged(tenant, it, StructureChange.CREATED, BoundaryFixture.entityNode()))
        }
    }

    fun <T> asUser(
        identity: String,
        block: () -> T,
    ): T = tenants.bind(tenant, identity) { block() }

    fun seed(
        identity: String,
        role: Role,
        entity: String? = null,
    ) = Seeds.grant(context, tenant, SeedSpec(identity, role, entity?.let { Scope.Entity(EntityRef(it)) }))

    /** A tax.approver whose scope follows the live consent, as an agent person holds it (INV-T-4939). */
    fun seedConsentDerived(
        identity: String,
        role: Role,
    ) = Seeds.grant(
        context,
        tenant,
        SeedSpec(identity, role, Scope.ConsentDerived(TenantRef(tenant.toString()), consents.check())),
    )

    fun grant(
        identity: String,
        role: String,
        scope: String,
        kind: GrantRequest.GranteeKind = GrantRequest.GranteeKind.STAFF,
        validTo: LocalDate = LocalDate.now().plusDays(400),
    ) = asUser(ADMIN) {
        grants.grantRole(
            UUID.randomUUID(),
            GrantRequest(kind, identity, role, scope, LocalDate.now().minusDays(1), validTo),
        )
    }

    fun acknowledge(): String =
        asUser(ADMIN) {
            soleOperator
                .recordSoleOperator(UUID.randomUUID(), SoleOperatorAckRequest(tenant.toString(), "external review"))
                .body!!
                .ackRef
        }

    fun withdraw(ackRef: String) =
        asUser(ADMIN) { soleOperator.withdrawSoleOperatorAcknowledgement(ackRef, UUID.randomUUID()) }

    fun pack(
        entity: String,
        author: String,
        approver: String,
        prior: List<String>? = null,
    ): PackApprovalDecision = asUser(ADMIN) { operations.decidePackApproval(entity, author, approver, prior).body!! }

    fun release(
        entity: String = "ENT-1",
        code: String = "PER-001",
        approver: String = "IDN-A1",
        total: String? = "1.00",
        prior: List<String>? = null,
        withDocument: Boolean = true,
    ): OverrideReleaseDecision =
        asUser(ADMIN) {
            operations
                .decideOverrideRelease(
                    entity,
                    code,
                    approver,
                    if (withDocument) "DOC-1" else null,
                    if (withDocument) 1 else null,
                    total?.let { BigDecimal(it) },
                    prior,
                ).body!!
        }

    fun refusal(block: () -> Any?): ApiRefusal {
        val thrown = runCatching { block() }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(ApiRefusal::class.java)
        return thrown as ApiRefusal
    }

    // ---- the setting path: the mounted servlet -------------------------------------------------------------------

    private fun servlet(): HttpServlet {
        val registrations = context.getBeansOfType(ServletRegistrationBean::class.java).values
        return registrations.single { it.urlMappings.contains(PolicyCommandServlet.MAPPING) }.servlet as HttpServlet
    }

    fun post(
        path: String,
        actor: String,
        body: String,
    ): MockHttpServletResponse {
        val request = MockHttpServletRequest("POST", path)
        request.contentType = "application/json"
        request.addHeader("Authorization", "Bearer tok:$actor")
        request.addHeader("Idempotency-Key", UUID.randomUUID().toString())
        request.setContent(body.toByteArray())
        return MockHttpServletResponse().also { servlet().service(request, it) }
    }

    private fun policyBody(
        level: String,
        entity: String,
        option: String,
        value: String?,
        extra: String,
    ): String {
        val today = BusinessDate.today(context.getBean(Clock::class.java))
        val valuePart = value?.let { ""","value":$it""" }.orEmpty()
        return """{"scopeLevel":"$level","scopeRef":"$entity","entityRef":"$entity",""" +
            """"selected":[{"option":"$option"$valuePart}],""" +
            """"effectiveDate":"$today","reason":"scenario"$extra}"""
    }

    /** `setPolicy` of [vp] for [entity]; [level] is ENTITY, or CLIENT_ENTITY for VP-04. */
    fun set(
        vp: String,
        entity: String,
        option: String,
        actor: String = ADMIN,
        level: String = "ENTITY",
        value: String? = null,
        relaxation: String? = null,
    ): MockHttpServletResponse {
        val extra = relaxation?.let { ""","relaxationId":"$it"""" }.orEmpty()
        return post("/internal/v1/policies/$vp", actor, policyBody(level, entity, option, value, extra))
    }

    /** The two calls of a looser value: the acknowledgement of `tenant.admin`, then the value with its id. */
    fun setLooser(
        vp: String,
        entity: String,
        option: String,
    ): MockHttpServletResponse {
        val ack =
            post(
                "/internal/v1/policies/$vp/relaxations",
                ADMIN,
                policyBody("ENTITY", entity, option, null, ""","asRole":"tenant.admin""""),
            )
        assertThat(ack.status).describedAs(ack.contentAsString).isBetween(200, 299)
        val id = json(ack.contentAsString).get("relaxationId").asString()
        return set(vp, entity, option, relaxation = id)
    }

    /** The two calls of a looser tenant-scope value (VP-81): the acknowledgement of `tenant.admin`, then the value. */
    fun setTenantLooser(
        vp: String,
        option: String,
    ): MockHttpServletResponse {
        val today = BusinessDate.today(context.getBean(Clock::class.java))

        fun body(extra: String) =
            """{"scopeLevel":"TENANT","scopeRef":"$tenant","selected":[{"option":"$option"}],""" +
                """"effectiveDate":"$today","reason":"scenario"$extra}"""
        val ack = post("/internal/v1/policies/$vp/relaxations", ADMIN, body(""","asRole":"tenant.admin""""))
        assertThat(ack.status).describedAs(ack.contentAsString).isBetween(200, 299)
        val id = json(ack.contentAsString).get("relaxationId").asString()
        return post("/internal/v1/policies/$vp", ADMIN, body(""","relaxationId":"$id""""))
    }

    fun json(text: String): JsonNode = mapper.readTree(text)

    fun accepted(response: MockHttpServletResponse) {
        assertThat(response.status).describedAs(response.contentAsString).isBetween(200, 299)
    }

    fun refused(response: MockHttpServletResponse) {
        assertThat(response.status).describedAs(response.contentAsString).isBetween(400, 499)
    }

    companion object {
        const val ADMIN = "user:tenant-admin"
    }
}

internal fun approvalRun(
    configure: (org.springframework.boot.test.context.runner.WebApplicationContextRunner) ->
    org.springframework.boot.test.context.runner.WebApplicationContextRunner = { it },
    block: (ApprovalEnv) -> Unit,
) {
    // each run has its own tenant: the database outlives a test, and policies and grants are stored per tenant
    val tenant = TenantId(UUID.randomUUID())
    configure(
        BoundaryFixture
            .runner(catalogueMatrix = true, requirements = null)
            .withBean(EndUserPrincipalResolver::class.java, { ActorTokens(tenant) }),
    ).run { context ->
        context.startupFailure?.let { throw it }
        block(ApprovalEnv(context, tenant))
    }
}
