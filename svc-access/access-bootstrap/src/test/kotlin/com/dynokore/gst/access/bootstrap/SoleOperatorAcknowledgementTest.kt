package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.http.AuthoriseController
import com.dynokore.gst.access.adapters.http.SoleOperatorController
import com.dynokore.gst.access.contract.model.AuthoriseResponse
import com.dynokore.gst.access.contract.model.SoleOperatorAckRequest
import com.dynokore.gst.access.contract.model.SoleOperatorAcknowledgementState
import com.dynokore.gst.access.domain.role.Allowance
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.platform.serviceruntime.replica.OrganisationStructureChanged
import com.dynokore.platform.serviceruntime.replica.StructureChange
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.util.UUID

/**
 * INV-T-8027 (INV-Q-7985 B, INV-REQ-875): the read operation `getSoleOperatorAcknowledgement` and the permission
 * `entity-config.confirm`. access-svc's part of INV-AC-2043, INV-AC-7946 and INV-AC-7947 is the permission cell and the
 * acknowledgement read; the refusal of a confirmation by the proposer is decided by svc-masterdata and svc-tenancy.
 */
class SoleOperatorAcknowledgementTest {
    private val key = "entity-config.confirm"
    private val holders = setOf("entity.admin", "tenant.admin", "tax.approver")
    private val admin = "user:tenant-admin"
    private val second = "user:second-admin"

    private fun freshTenant() = TenantId(UUID.randomUUID())

    private inner class Env(
        val context: AssertableWebApplicationContext,
        val tenant: TenantId,
    ) {
        val tenants = context.getBean(TenantContext::class.java)
        val soleOperator = context.getBean(SoleOperatorController::class.java)

        init {
            Seeds.grant(context, tenant, SeedSpec(admin, Role.TENANT_ADMIN))
            // a second person, so that the acknowledgement may be withdrawn under the default of VP-81
            Seeds.grant(context, tenant, SeedSpec(second, Role.TENANT_ADMIN))
        }

        fun <T> asAdmin(
            bound: TenantId = tenant,
            block: () -> T,
        ): T = tenants.bind(bound, admin) { block() }

        fun record(): String =
            asAdmin {
                soleOperator
                    .recordSoleOperator(UUID.randomUUID(), SoleOperatorAckRequest(tenant.toString(), "external audit"))
                    .body!!
                    .ackRef
            }

        fun withdraw(ackRef: String) =
            asAdmin { soleOperator.withdrawSoleOperatorAcknowledgement(ackRef, UUID.randomUUID()) }

        fun read(
            tenantRef: String = tenant.toString(),
            bound: TenantId = tenant,
        ): SoleOperatorAcknowledgementState =
            asAdmin(bound) { soleOperator.getSoleOperatorAcknowledgement(tenantRef).body!! }

        fun rows(): String =
            BoundaryFixture.asSuperuser { c ->
                c.createStatement().use { s ->
                    s.executeQuery("select * from access.sole_operator_ack order by id").use { r ->
                        val n = r.metaData.columnCount
                        val out = StringBuilder()
                        while (r.next()) (1..n).forEach { out.append(r.getString(it)).append(',') }
                        out.toString()
                    }
                }
            }
    }

    private fun run(block: (Env) -> Unit) {
        BoundaryFixture.runner(catalogueMatrix = true, form = "SAAS").run { context ->
            context.startupFailure?.let { throw it }
            block(Env(context, freshTenant()))
        }
    }

    @Test
    fun `INV-AC-7947 the read answers recorded with the ackRef, then not recorded after withdrawal`() =
        run { env ->
            assertThat(env.read()).isEqualTo(SoleOperatorAcknowledgementState(env.tenant.toString(), false, null))
            val ackRef = env.record()
            assertThat(env.read()).isEqualTo(SoleOperatorAcknowledgementState(env.tenant.toString(), true, ackRef))
            assertThat(env.withdraw(ackRef).statusCode.value()).isEqualTo(204)
            assertThat(env.read()).isEqualTo(SoleOperatorAcknowledgementState(env.tenant.toString(), false, null))
        }

    @Test
    fun `INV-AC-2043 the acknowledgement of tenant A is never reported for tenant B`() =
        run { env ->
            env.record()
            val other = freshTenant()
            // the caller of tenant A asks for B, and the caller of B asks for B
            assertThat(env.read(other.toString()).recorded).isFalse()
            assertThat(env.read(other.toString(), bound = other).recorded).isFalse()
            assertThat(env.read(other.toString(), bound = other).ackRef).isNull()
        }

    @Test
    fun `the read writes nothing and a request without tenantRef is refused 400 and changes nothing`() =
        run { env ->
            env.record()
            val before = env.rows()
            env.read()
            env.read(freshTenant().toString())
            val mvc =
                MockMvcBuilders
                    .webAppContextSetup(env.context.sourceApplicationContext as WebApplicationContext)
                    .build()
            val status =
                mvc
                    .perform(MockMvcRequestBuilders.get("/internal/v1/access/sole-operator-acknowledgements"))
                    .andReturn()
                    .response.status
            assertThat(status).isEqualTo(400)
            assertThat(env.rows()).isEqualTo(before)
        }

    @Test
    fun `INV-AC-7946 the permission entity-config-confirm is held by exactly three roles in both forms`() {
        DeploymentForm.entries.forEach { form ->
            val findings = cellFindings(form, PermissionMatrixLoader.load(form).export())
            assertThat(findings).describedAs("form $form").isEmpty()
        }
    }

    /** The roles whose allowance for [key] differs from the expectation, each naming role, permission and form. */
    private fun cellFindings(
        form: DeploymentForm,
        exported: Map<String, Map<String, Allowance>>,
    ): List<String> =
        Role.entries.mapNotNull { role ->
            val want = if (role.wire in holders) Allowance.PERMITTED else null
            val have = exported[role.wire]?.get(key)
            if (want == have) null else "role ${role.wire} permission $key form $form: want $want have $have"
        }

    @Test
    fun `the 19 roles are compared and a changed cell of a matrix copy fails naming role, permission, form`() {
        assertThat(Role.entries).hasSize(19)
        val form = DeploymentForm.SAAS
        val exported = PermissionMatrixLoader.load(form).export()

        fun copy() = exported.mapValues { it.value.toMutableMap() }.toMutableMap()

        val removed = copy().also { it.getValue("tax.approver").remove(key) }
        assertThat(cellFindings(form, removed))
            .containsExactly("role tax.approver permission $key form SAAS: want PERMITTED have null")

        val added = copy().also { it.getValue("finance.viewer")[key] = Allowance.PERMITTED }
        assertThat(cellFindings(form, added))
            .containsExactly("role finance.viewer permission $key form SAAS: want null have PERMITTED")

        val renamed =
            copy().also { cells -> cells.values.forEach { row -> row.remove(key)?.let { row["${key}s"] = it } } }
        assertThat(cellFindings(form, renamed)).hasSize(holders.size)
        holders.forEach { role ->
            assertThat(cellFindings(form, renamed))
                .contains("role $role permission $key form SAAS: want PERMITTED have null")
        }
    }

    private fun decisions(
        form: String,
        block: (Map<String, AuthoriseResponse.Decision>) -> Unit,
    ) {
        BoundaryFixture.runner(catalogueMatrix = true, form = form).run { context ->
            context.startupFailure?.let { throw it }
            val tenant = freshTenant()
            val tenants = context.getBean(TenantContext::class.java)
            val api = context.getBean(AuthoriseController::class.java)
            val roles = listOf(Role.TAX_APPROVER, Role.ENTITY_ADMIN, Role.TENANT_ADMIN, Role.FINANCE_VIEWER)
            roles.forEach { Seeds.grant(context, tenant, SeedSpec("user:only-${it.wire}", it)) }
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
            val answers =
                roles.associate {
                    it.wire to
                        tenants.bind(tenant, "user:only-${it.wire}") {
                            api.authorise("user:only-${it.wire}", key, "entity:ENT-1").body!!.decision
                        }
                }
            block(answers)
        }
    }

    @Test
    fun `INV-AC-7946 authorise answers ALLOW for the three holder roles and DENY for finance-viewer`() {
        listOf("SAAS", "PRIVATE").forEach { form ->
            decisions(form) { answers ->
                assertThat(answers)
                    .describedAs("form $form")
                    .containsEntry("tax.approver", AuthoriseResponse.Decision.ALLOW)
                    .containsEntry("entity.admin", AuthoriseResponse.Decision.ALLOW)
                    .containsEntry("tenant.admin", AuthoriseResponse.Decision.ALLOW)
                    .containsEntry("finance.viewer", AuthoriseResponse.Decision.DENY)
            }
        }
    }

    private val operations = java.io.File("../operations.tsv").readLines()

    /** The findings of the comparison of the `task` column of the row [name] with [task] by equality. */
    private fun rowFindings(
        lines: List<String>,
        name: String,
        task: String,
    ): List<String> {
        val rows = lines.map { it.split('\t') }.filter { it.size > 1 && it[1] == name }
        return when {
            rows.size != 1 -> listOf("$name has ${rows.size} rows")
            rows.single()[2] != task -> listOf("$name names ${rows.single()[2]}")
            else -> emptyList()
        }
    }

    @Test
    fun `INV-AC-7947 the operations rows name INV-T-8027 for the read and INV-T-4935 for recordSoleOperator`() {
        assertThat(rowFindings(operations, "getSoleOperatorAcknowledgement", "INV-T-8027")).isEmpty()
        assertThat(rowFindings(operations, "recordSoleOperator", "INV-T-4935")).isEmpty()
        // probes: a look-alike task, a missing row, and the row of recordSoleOperator naming this task
        val lookAlike = operations.map { it.replace("INV-T-8027", "INV-T-80270") }
        assertThat(rowFindings(lookAlike, "getSoleOperatorAcknowledgement", "INV-T-8027"))
            .containsExactly("getSoleOperatorAcknowledgement names INV-T-80270")
        val missing = operations.filter { !it.contains("getSoleOperatorAcknowledgement") }
        assertThat(rowFindings(missing, "getSoleOperatorAcknowledgement", "INV-T-8027"))
            .containsExactly("getSoleOperatorAcknowledgement has 0 rows")
        val swapped =
            operations.map {
                if (it.contains("\trecordSoleOperator\t")) it.replace("INV-T-4935", "INV-T-8027") else it
            }
        assertThat(rowFindings(swapped, "recordSoleOperator", "INV-T-4935"))
            .containsExactly("recordSoleOperator names INV-T-8027")
    }
}
