package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.http.ApiRefusal
import com.dynokore.gst.access.adapters.http.AuthoriseController
import com.dynokore.gst.access.adapters.http.GrantsController
import com.dynokore.gst.access.adapters.http.SoleOperatorController
import com.dynokore.gst.access.contract.model.AuthoriseResponse.Decision
import com.dynokore.gst.access.contract.model.GrantRequest
import com.dynokore.gst.access.contract.model.LandingChoiceRequest
import com.dynokore.gst.access.contract.model.SoleOperatorAckRequest
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.gst.access.domain.role.RoleCatalogue
import com.dynokore.platform.configresolver.Actor
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.PolicyWriter
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.SetPolicyCommand
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.serviceruntime.replica.OrganisationStructureChanged
import com.dynokore.platform.serviceruntime.replica.StructureChange
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * The acceptance criteria of INV-T-4935 at the service boundary: the real application context over a real PostgreSQL,
 * the permission matrix and the role catalogue built by the loader from the gst-catalogue table of the installation's
 * form (no fixture rows), the controllers called under a bound tenant. Test names carry the criterion key
 * (acKeyCoverage).
 */
class CatalogueAcceptanceTest {
    private val tenantA = TenantId(UUID.fromString("0a000000-0000-4000-8000-00000000000a"))
    private val tenantB = TenantId(UUID.fromString("0b000000-0000-4000-8000-00000000000b"))
    private val admin = "user:tenant-admin"

    private inner class Env(
        val context: AssertableWebApplicationContext,
        val tenant: TenantId,
    ) {
        val tenants = context.getBean(TenantContext::class.java)
        val grants = context.getBean(GrantsController::class.java)
        val authorise = context.getBean(AuthoriseController::class.java)
        val soleOperator = context.getBean(SoleOperatorController::class.java)
        val catalogue = context.getBean(RoleCatalogue::class.java)
        val matrix = context.getBean(PermissionMatrix::class.java)

        init {
            Seeds.grant(context, tenant, SeedSpec(admin, Role.TENANT_ADMIN))
            context
                .getBean(StructureReplica::class.java)
                .accept(
                    OrganisationStructureChanged(
                        tenant,
                        "ENT-1",
                        StructureChange.CREATED,
                        BoundaryFixture.entityNode(),
                    ),
                )
        }

        fun <T> asUser(
            identity: String,
            block: () -> T,
        ): T = tenants.bind(tenant, identity) { block() }

        fun grant(
            identity: String,
            role: String,
        ) = asUser(admin) {
            grants.grantRole(
                UUID.randomUUID(),
                GrantRequest(
                    GrantRequest.GranteeKind.STAFF,
                    identity,
                    role,
                    "entity:ENT-1",
                    LocalDate.now().minusDays(1),
                    LocalDate.now().plusDays(400),
                ),
            )
        }

        fun decide(
            identity: String,
            permission: String,
        ): Decision = asUser(admin) { authorise.authorise(identity, permission, "entity:ENT-1").body!!.decision }

        fun refused(
            identity: String,
            role: String,
        ): Boolean = runCatching { grant(identity, role) }.exceptionOrNull() is ApiRefusal
    }

    private fun run(
        form: String = "SAAS",
        tenant: TenantId = tenantA,
        block: (Env) -> Unit,
    ) {
        BoundaryFixture.runner(catalogueMatrix = true, form = form).run { context ->
            context.startupFailure?.let { throw it }
            block(Env(context, tenant))
        }
    }

    private fun Env.holding(
        identity: String,
        role: String,
    ): String {
        grant(identity, role)
        return identity
    }

    @Test
    fun `INV-AC-1925 INV-AC-7943 tenant admin manages users and credentials, not an identity triple`() =
        run { env ->
            val user = env.holding("IDN-TA", "tenant.admin")
            assertThat(env.decide(user, "user.manage")).isEqualTo(Decision.ALLOW)
            assertThat(env.decide(user, "ap-credential.manage")).isEqualTo(Decision.ALLOW)
            assertThat(env.decide(user, "entity-config.manage")).isEqualTo(Decision.DENY)
        }

    @Test
    fun `INV-AC-1926 INV-AC-7944 only entity admin changes an election`() =
        run { env ->
            val entityAdmin = env.holding("IDN-EA", "entity.admin")
            val tenantAdmin = env.holding("IDN-TA", "tenant.admin")
            assertThat(env.decide(entityAdmin, "entity-config.manage")).isEqualTo(Decision.ALLOW)
            assertThat(env.decide(tenantAdmin, "entity-config.manage")).isEqualTo(Decision.DENY)
        }

    @Test
    fun `INV-AC-1928 INV-AC-7949 a private installation has none of the four operator roles`() =
        run("PRIVATE") { env ->
            val operatorRoles = listOf("platform.support", "platform.engineer", "release.manager", "commercial.admin")
            val names = env.catalogue.roles.map { it.wire }
            operatorRoles.forEach { role ->
                assertThat(names).noneMatch { it == role }
                assertThat(env.refused("IDN-X", role)).describedAs("grant of $role").isTrue()
            }
            assertThat(names).contains("tenant.admin", "deployment.operator")
        }

    @Test
    fun `INV-AC-7952 security officer exists in the role catalogue of both forms`() {
        run("SAAS") { env -> assertThat(env.catalogue.roles.map { it.wire }).anyMatch { it == "security.officer" } }
        run("PRIVATE") { env -> assertThat(env.catalogue.roles.map { it.wire }).anyMatch { it == "security.officer" } }
    }

    @Test
    fun `INV-AC-7948 private deployment operator has no content, no arming, bundle export`() {
        run("PRIVATE") { env ->
            val operator = env.holding("IDN-OP", "deployment.operator")
            assertThat(env.decide(operator, "doc.view.content")).isEqualTo(Decision.DENY)
            assertThat(env.decide(operator, "support-bundle.export")).isEqualTo(Decision.ALLOW)
            assertThat(env.decide(operator, "regulatory-pack.arm")).isEqualTo(Decision.DENY)
        }
        run("SAAS") { env ->
            assertThat(env.catalogue.roles.map { it.wire }).noneMatch { it == "deployment.operator" }
            assertThat(env.refused("IDN-OP", "deployment.operator")).isTrue()
        }
    }

    @Test
    fun `INV-AC-1047 INV-AC-7955 private tax approver arms, deployment operator imports only`() =
        run("PRIVATE") { env ->
            val operator = env.holding("IDN-OP", "deployment.operator")
            val approver = env.holding("IDN-AP", "tax.approver")
            assertThat(env.decide(operator, "regulatory-pack.import-verify")).isEqualTo(Decision.ALLOW)
            assertThat(env.decide(operator, "regulatory-pack.arm")).isEqualTo(Decision.DENY)
            assertThat(env.decide(approver, "regulatory-pack.arm")).isEqualTo(Decision.ALLOW)
        }

    @Test
    fun `INV-AC-7951 INV-AC-1929 a private retention deletion permission is refused to the other role`() =
        run("PRIVATE") { env ->
            val operator = env.holding("IDN-OP", "deployment.operator")
            val approver = env.holding("IDN-AP", "tax.approver")
            assertThat(env.decide(operator, "retention.delete.approve")).isEqualTo(Decision.DENY)
            assertThat(env.decide(approver, "retention.delete.request")).isEqualTo(Decision.DENY)
            assertThat(env.decide(operator, "retention.delete.request")).isEqualTo(Decision.ALLOW)
            assertThat(env.decide(approver, "retention.delete.approve")).isEqualTo(Decision.ALLOW)
        }

    @Test
    fun `INV-AC-1931 INV-AC-7954 security officer on a tenant admin needs the acknowledgement`() =
        run { env ->
            val person = env.holding("IDN-SO", "tenant.admin")
            assertThat(env.refused(person, "security.officer")).isTrue()
            env.asUser(admin) {
                env.soleOperator.recordSoleOperator(
                    UUID.randomUUID(),
                    SoleOperatorAckRequest(env.tenant.toString(), "all changes are reviewed by the external auditor"),
                )
            }
            assertThat(env.refused(person, "security.officer")).isFalse()
        }

    @Test
    fun `INV-AC-1931 the other order is refused too and a person with one of the two roles is not`() =
        run(tenant = tenantB) { env ->
            val person = env.holding("IDN-SO2", "security.officer")
            assertThat(env.refused(person, "tenant.admin")).isTrue()
            assertThat(env.refused("IDN-OTHER", "security.officer")).isFalse()
        }

    @Test
    fun `INV-AC-2767 landing follows the precedence until the person chooses and no permission changes`() =
        run { env ->
            val person = "IDN-TWO"
            env.grant(person, "tax.approver")
            env.grant(person, "tenant.admin")
            val before = env.matrix.export()

            fun landing() =
                env.asUser(person) {
                    env.grants
                        .resolveLandingScreen()
                        .body!!
                        .role
                }
            assertThat(landing()).isEqualTo("tenant.admin")
            env.asUser(person) {
                env.grants.chooseLandingScreen(UUID.randomUUID(), LandingChoiceRequest("tax.approver"))
            }
            assertThat(landing()).isEqualTo("tax.approver")
            env.asUser(person) { env.grants.clearLandingChoice(UUID.randomUUID()) }
            assertThat(landing()).isEqualTo("tenant.admin")
            assertThat(env.matrix.export()).isEqualTo(before)
        }

    @Test
    fun `INV-AC-7956 only held roles are candidates and a precedence change leaves the matrix identical`() =
        run { env ->
            val person = "IDN-TWO"
            env.grant(person, "tax.approver")
            env.grant(person, "tenant.admin")
            val before = env.matrix.export()
            val refusal =
                runCatching {
                    env.asUser(person) {
                        env.grants.chooseLandingScreen(UUID.randomUUID(), LandingChoiceRequest("entity.admin"))
                    }
                }.exceptionOrNull()
            assertThat(refusal).isInstanceOf(ApiRefusal::class.java)
            val writer = env.context.getBean(PolicyWriter::class.java)
            val tx = env.context.getBean(TransactionRunner::class.java)
            val precedence = """["tax.approver","tenant.admin"]"""
            val command =
                SetPolicyCommand(
                    key = VariationPointKey("VP-73"),
                    scope = PolicyScope.tenant(env.tenant),
                    level = ScopeLevel.TENANT,
                    selected = listOf(SelectedOption("TENANT_PRECEDENCE", precedence)),
                    effectiveFrom = LocalDate.now(ZoneId.of("Asia/Singapore")),
                    reason = "precedence",
                    actor = Actor(admin, setOf("tenant.admin")),
                    correlation = CorrelationId("c-4935"),
                    causation = CausationId("c-4935"),
                )
            env.asUser(admin) { tx.inTransaction { writer.setPolicy(command) } }
            assertThat(
                env.asUser(person) {
                    env.grants
                        .resolveLandingScreen()
                        .body!!
                        .role
                },
            ).isEqualTo("tax.approver")
            assertThat(env.matrix.export()).isEqualTo(before)
        }

    @Test
    fun `the start-up stops when the catalogue lists a role this service does not know`() {
        assertThatThrownBy {
            PermissionMatrixLoader.load(
                com.dynokore.gst.catalogue.DeploymentForm.SAAS,
                object : CatalogueTable by CatalogueTable.PUBLISHED {
                    override fun cells(form: com.dynokore.gst.catalogue.DeploymentForm) =
                        CatalogueTable.PUBLISHED.cells(form) + ("tenant.admins" to emptyMap())
                },
            )
        }.hasMessageContaining("tenant.admins")
    }

    @Test
    fun `INV-AC-9076 a private installation grants deployment operator and the other roles of its catalogue`() =
        run("PRIVATE") { env ->
            val operatorWires =
                setOf(
                    "platform.support",
                    "platform.engineer",
                    "release.manager",
                    "commercial.admin",
                    "regulatory.author",
                    "regulatory.approver",
                )
            val others =
                env.catalogue.roles
                    .map { it.wire }
                    .filter { it !in operatorWires }
            assertThat(others).contains("deployment.operator", "implementation.consultant", "security.officer")
            others.forEach { role ->
                val response = env.grant("IDN-9076-$role", role)
                assertThat(response.statusCode.value()).describedAs("grantRole $role").isEqualTo(201)
            }
        }
}
