package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.http.ApiRefusal
import com.dynokore.gst.tenancy.adapters.http.WorkspacesController
import com.dynokore.gst.tenancy.contract.model.CreateTenantRequest
import com.dynokore.gst.tenancy.contract.model.CreateWorkspaceRequest
import com.dynokore.gst.tenancy.contract.model.WorkspaceResponse
import com.dynokore.gst.tenancy.domain.TenantRefs
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.sql.ResultSet
import java.util.UUID

/**
 * createWorkspace and archiveWorkspace through the controller of the real application context over PostgreSQL with
 * forced row-level security (INV-REQ-1125): the tenant rows are read through the fixture's owner connection.
 */
class WorkspaceTest {
    private fun run(block: (ProfileEnv, WorkspacesController) -> Unit) {
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            block(ProfileEnv(context), context.getBean(WorkspacesController::class.java))
        }
    }

    /** One tenant row as the owner connection reads it: every column, so a changed row differs. */
    private fun row(tenant: UUID): String =
        BoundaryFixture.asSuperuser { c ->
            c.prepareStatement("select t::text from tenancy.tenant t where t.tenant_id = ?").use { s ->
                s.setObject(1, tenant)
                s.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else "none" }
            }
        }

    private fun count(
        sql: String,
        tenant: UUID? = null,
    ): Long =
        BoundaryFixture.asSuperuser { c ->
            c.prepareStatement(sql).use { s ->
                if (tenant != null) s.setObject(1, tenant)
                s.executeQuery().use { rs: ResultSet ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        }

    private fun tenants(): Long = count("select count(*) from tenancy.tenant")

    private fun siblings(production: UUID): Long =
        count(
            "select count(*) from tenancy.tenant where environment = 'SANDBOX' and " +
                "paired_production_tenant_id = ?",
            production,
        )

    private fun unarchived(production: UUID): Long =
        count(
            "select count(*) from tenancy.tenant where environment = 'SANDBOX' and " +
                "paired_production_tenant_id = ? and not retired",
            production,
        )

    /** The INV-AC-935 comparison: a SANDBOX tenant exists whose paired production tenant is [production]. */
    private fun hasSandboxPairedTo(production: UUID): Boolean =
        count(
            "select count(*) from tenancy.tenant where environment = 'SANDBOX' and " +
                "paired_production_tenant_id = ?",
            production,
        ) > 0

    /** A new tenant, left unbound: createTenant runs under the platform binding of its own. */
    private fun ProfileEnv.newTenant(): TenantId {
        val contract = "C-${UUID.randomUUID()}"
        val ref = TenantRefs.refOfContract(contract)
        tenantsApi.createTenant(UUID.randomUUID(), CreateTenantRequest(contract, "Tenant $contract", "PLAN-A", ref))
        return TenantRefs.idOf(ref)
    }

    private fun create(
        api: WorkspacesController,
        production: TenantId,
        name: String,
    ) = api.createWorkspace(UUID.randomUUID(), CreateWorkspaceRequest(production.value.toString(), name))

    @Test
    fun `INV-AC-935 a created workspace is a SANDBOX tenant paired to production, whose row is unchanged`() {
        run { env, api ->
            env.withTenant { production ->
                val before = row(production.value)
                val response = create(api, production, "pre-prod")
                assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
                assertThat(response.body!!.environment).isEqualTo(WorkspaceResponse.Environment.SANDBOX)
                assertThat(hasSandboxPairedTo(production.value)).isTrue()
                val sibling = TenantRefs.idOf(response.body!!.workspaceRef).value
                assertThat(row(sibling)).contains("SANDBOX").contains(production.value.toString())
                assertThat(row(production.value)).isEqualTo(before)
            }
        }
    }

    @Test
    fun `INV-AC-935 probe a sandbox tenant paired to another production tenant makes the comparison fail`() {
        run { env, api ->
            val production = env.newTenant()
            val other = env.newTenant()
            env.inTenant(other) { create(api, other, "pre-prod") }
            assertThat(hasSandboxPairedTo(other.value)).isTrue()
            assertThat(hasSandboxPairedTo(production.value)).isFalse()
        }
    }

    @Test
    fun `createWorkspace takes its ref from TenantRefs, a repeat adds no row and another name adds a sibling`() {
        run { env, api ->
            env.withTenant { production ->
                val ref = TenantRefs.refOfWorkspace(production.value.toString(), "pre-prod")
                val start = tenants()
                val first = create(api, production, "pre-prod").body!!
                assertThat(first.workspaceRef).isEqualTo(ref)
                assertThat(tenants()).isEqualTo(start + 1)
                val repeat = create(api, production, "pre-prod").body!!
                assertThat(repeat.workspaceRef).isEqualTo(first.workspaceRef)
                assertThat(tenants()).isEqualTo(start + 1)
                val other = create(api, production, "pre-prod-2").body!!
                assertThat(other.workspaceRef).isNotEqualTo(first.workspaceRef)
                assertThat(tenants()).isEqualTo(start + 2)
                val sibling = row(TenantRefs.idOf(ref).value)
                assertThat(sibling).contains(ref)
            }
        }
    }

    @Test
    fun `createWorkspace copies the legal name and plan of the production tenant`() {
        run { env, api ->
            env.withTenant { production ->
                val ref = create(api, production, "pre-prod").body!!.workspaceRef
                val columns =
                    BoundaryFixture.asSuperuser { c ->
                        c
                            .prepareStatement(
                                "select s.legal_name = p.legal_name and s.plan_ref = p.plan_ref " +
                                    "and s.contract_reference = s.ref " +
                                    "from tenancy.tenant s join tenancy.tenant p on p.tenant_id = ? where s.ref = ?",
                            ).use { s ->
                                s.setObject(1, production.value)
                                s.setString(2, ref)
                                s.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
                            }
                    }
                assertThat(columns).isTrue()
            }
        }
    }

    @Test
    fun `a caller bound to another tenant than the one it names is refused and nothing is written`() {
        run { env, api ->
            val production = env.newTenant()
            val other = env.newTenant()
            val start = tenants()
            env.inTenant(other) {
                // bound to `other`, naming `production`
                assertThatThrownBy { create(api, production, "pre-prod") }
                    .isInstanceOf(ApiRefusal::class.java)
                    .matches { (it as ApiRefusal).code == ClosedErrorCode.ACCESS_DENIED }
            }
            assertThat(tenants()).isEqualTo(start)
            assertThat(siblings(production.value)).isZero()
        }
    }

    @Test
    fun `a workspace of a workspace is refused`() {
        run { env, api ->
            env.withTenant { production ->
                val workspace = TenantRefs.idOf(create(api, production, "pre-prod").body!!.workspaceRef)
                env.inTenant(workspace) {
                    assertThatThrownBy { create(api, workspace, "nested") }
                        .isInstanceOf(ApiRefusal::class.java)
                        .matches { (it as ApiRefusal).code == ClosedErrorCode.CONTRACT_VIOLATION }
                }
                assertThat(siblings(workspace.value)).isZero()
            }
        }
    }

    @Test
    fun `INV-AC-9574 creating a workspace twice yields one sibling and archiving twice archives once`() {
        run { env, api ->
            env.withTenant { production ->
                val before = row(production.value)
                val first = create(api, production, "pre-prod").body!!
                val repeat = create(api, production, "pre-prod").body!!
                create(api, production, "pre-prod-2")
                assertThat(siblings(production.value)).isEqualTo(2)
                assertThat(repeat.workspaceRef).isEqualTo(first.workspaceRef)
                val firstArchive = api.archiveWorkspace(first.workspaceRef, UUID.randomUUID())
                val secondArchive = api.archiveWorkspace(first.workspaceRef, UUID.randomUUID())
                assertThat(firstArchive.statusCode.value()).isEqualTo(204)
                assertThat(secondArchive.statusCode.value()).isEqualTo(204)
                assertThat(unarchived(production.value)).isEqualTo(1)
                assertThat(row(production.value)).isEqualTo(before)
            }
        }
    }

    @Test
    fun `archiveWorkspace answers not found for a ref that is no workspace of the bound tenant`() {
        run { env, api ->
            val production = env.newTenant()
            val other = env.newTenant()
            val own = env.inTenant(production) { create(api, production, "pre-prod").body!!.workspaceRef }
            env.inTenant(other) {
                assertThatThrownBy { api.archiveWorkspace(own, UUID.randomUUID()) }
                    .isInstanceOf(ApiRefusal::class.java)
                    .matches { (it as ApiRefusal).code == ClosedErrorCode.NOT_FOUND }
                assertThatThrownBy { api.archiveWorkspace(other.value.toString(), UUID.randomUUID()) }
                    .isInstanceOf(ApiRefusal::class.java)
            }
            assertThat(unarchived(production.value)).isEqualTo(1)
        }
    }
}
