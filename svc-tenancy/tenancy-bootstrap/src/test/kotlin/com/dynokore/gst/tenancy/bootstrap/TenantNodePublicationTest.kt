package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.http.WorkspacesController
import com.dynokore.gst.tenancy.application.structure.StructureChange
import com.dynokore.gst.tenancy.application.structure.StructureChangePublisher
import com.dynokore.gst.tenancy.application.tenant.CreateTenant
import com.dynokore.gst.tenancy.application.tenant.TenantRecord
import com.dynokore.gst.tenancy.application.tenant.TenantStore
import com.dynokore.gst.tenancy.application.tenant.WorkspaceStore
import com.dynokore.gst.tenancy.application.workspace.ArchiveWorkspace
import com.dynokore.gst.tenancy.application.workspace.CreateWorkspace
import com.dynokore.gst.tenancy.contract.model.CreateTenantRequest
import com.dynokore.gst.tenancy.contract.model.CreateWorkspaceRequest
import com.dynokore.gst.tenancy.domain.TenantRefs
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.ReplayStateSource
import com.dynokore.platform.productruntime.ProductEventTypes
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

private val tree = JsonMapper()

/**
 * INV-AC-15381 over PostgreSQL with forced row-level security (INV-Q-15377 A): the TENANT events createTenant,
 * createWorkspace and archiveWorkspace write in the transaction of the tenant row, compared with the replay.
 */
class TenantNodePublicationTest {
    private fun run(block: (ProfileEnv) -> Unit) {
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            block(ProfileEnv(context))
        }
    }

    private fun scalar(
        sql: String,
        arg: Any,
    ): Long =
        BoundaryFixture.asSuperuser { c ->
            c.prepareStatement(sql).use { s ->
                s.setObject(1, arg)
                s.executeQuery().use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        }

    private fun rows(ref: String) = scalar("select count(*) from tenancy.tenant where ref = ?", ref)

    private fun retired(ref: String) = scalar("select count(*) from tenancy.tenant where ref = ? and retired", ref)

    private fun ProfileEnv.created(): Pair<String, TenantId> {
        val contract = "C-${UUID.randomUUID()}"
        val ref = TenantRefs.refOfContract(contract)
        captured.envelopes.clear()
        tenantsApi.createTenant(UUID.randomUUID(), CreateTenantRequest(contract, "Tenant $contract", "PLAN-A", ref))
        return ref to TenantRefs.idOf(ref)
    }

    private fun ProfileEnv.events(ref: String) =
        captured.envelopes.filter {
            it.type == ProductEventTypes.ORGANISATION_STRUCTURE_CHANGED && it.subject == ref
        }

    /** The replay state of the bound tenant's own TENANT node, as JSON. */
    private fun ProfileEnv.replayed(tenant: TenantId): JsonNode {
        val source =
            context.getBeansOfType(ReplayStateSource::class.java).values.single {
                it.eventType == ProductEventTypes.ORGANISATION_STRUCTURE_CHANGED
            }
        val state =
            inTenant(tenant) { inTx { source.page(null, null, 100) } }
                .items
                .first { it.aggregateKey == tenant.value.toString() }
        assertThat(state.entity).isEqualTo(EntityRef(tenant.value.toString()))
        return tree.readTree(state.dataJson)
    }

    private fun withoutChangeType(node: JsonNode) =
        (node.deepCopy() as tools.jackson.databind.node.ObjectNode).also { it.remove("changeType") }

    private fun workspaces(env: ProfileEnv) = env.context.getBean(WorkspacesController::class.java)

    @Test
    fun `INV-AC-15381 createTenant writes one CREATED event equal to the replay, a repeat none`() {
        run { env ->
            val (ref, tenant) = env.created()
            val live = env.events(ref).single()
            val payload = tree.readTree(live.dataJson)
            assertThat(payload["nodeType"].asString()).isEqualTo("TENANT")
            assertThat(payload["nodeRef"].asString()).isEqualTo(ref)
            assertThat(payload["changeType"].asString()).isEqualTo("CREATED")
            assertThat(payload.has("parentRef")).isFalse()
            assertThat(payload.has("previousParentRef")).isFalse()
            assertThat(payload["node"]["environment"].asString()).isEqualTo("PRODUCTION")
            val replay = env.replayed(tenant)
            assertThat(replay["changeType"].asString()).isEqualTo("UPDATED")
            assertThat(withoutChangeType(payload)).isEqualTo(withoutChangeType(replay))
            assertThat(live.subject).isEqualTo(ref)
            assertThat(live.entity).isEqualTo(EntityRef(ref))
            // a repeat of the same call writes nothing
            val before = env.captured.envelopes.size
            val contract = scalar("select count(*) from tenancy.tenant where ref = ?", ref)
            assertThat(contract).isEqualTo(1)
            env.tenantsApi.createTenant(
                UUID.randomUUID(),
                CreateTenantRequest(
                    BoundaryFixture.asSuperuser { c ->
                        c.prepareStatement("select contract_reference from tenancy.tenant where ref = ?").use { s ->
                            s.setObject(1, ref)
                            s.executeQuery().use { rs ->
                                rs.next()
                                rs.getString(1)
                            }
                        }
                    },
                    "Tenant",
                    "PLAN-A",
                    ref,
                ),
            )
            assertThat(env.captured.envelopes.size).isEqualTo(before)
        }
    }

    @Test
    fun `INV-AC-15381 createWorkspace and archiveWorkspace write the sibling's own SANDBOX events and a repeat none`() {
        run { env ->
            env.withTenant { production ->
                env.captured.envelopes.clear()
                val api = workspaces(env)
                val ref =
                    api
                        .createWorkspace(UUID.randomUUID(), CreateWorkspaceRequest(production.value.toString(), "uat"))
                        .body!!
                        .workspaceRef
                val sibling = TenantRefs.idOf(ref)
                val created = env.events(ref).single()
                val payload = tree.readTree(created.dataJson)
                assertThat(payload["changeType"].asString()).isEqualTo("CREATED")
                assertThat(payload["node"]["environment"].asString()).isEqualTo("SANDBOX")
                assertThat(created.tenant.value).isEqualTo(sibling.value)
                assertThat(env.events(production.value.toString())).isEmpty()
                val versionAtCreation = payload["node"]["structureVersion"].asLong()
                env.inTenant(sibling) {
                    val replay = env.replayed(sibling)
                    assertThat(withoutChangeType(payload)).isEqualTo(withoutChangeType(replay))
                }
                api.createWorkspace(UUID.randomUUID(), CreateWorkspaceRequest(production.value.toString(), "uat"))
                assertThat(env.events(ref)).hasSize(1)

                api.archiveWorkspace(ref, UUID.randomUUID())
                val all = env.events(ref)
                assertThat(all).hasSize(2)
                val retired = tree.readTree(all.last().dataJson)
                assertThat(retired["changeType"].asString()).isEqualTo("RETIRED")
                assertThat(retired["node"]["retired"].asBoolean()).isTrue()
                assertThat(retired["node"]["environment"].asString()).isEqualTo("SANDBOX")
                assertThat(retired["node"]["structureVersion"].asLong()).isGreaterThan(versionAtCreation)
                assertThat(all.last().tenant.value).isEqualTo(sibling.value)
                assertThat(retired).isEqualTo(env.replayed(sibling))
                api.archiveWorkspace(ref, UUID.randomUUID())
                assertThat(env.events(ref)).hasSize(2)
            }
        }
    }

    private val boom =
        object : StructureChangePublisher {
            override fun publish(change: StructureChange) = error("outbox refused")
        }

    @Test
    fun `INV-AC-15381 a publisher that throws leaves no new tenant row and keeps retired false`() {
        run { env ->
            val ctx = env.context
            val tx = ctx.getBean(TransactionRunner::class.java)
            val tenants = ctx.getBean(TenantContext::class.java)
            val contract = "C-${UUID.randomUUID()}"
            val ref = TenantRefs.refOfContract(contract)
            val record = TenantRecord(TenantRefs.idOf(ref).value, ref, "Name", "PLAN-A", contract)
            val failing =
                CreateTenant(
                    tenants as PlatformTenantBinding,
                    tx,
                    ctx.getBean(TenantStore::class.java),
                    "tenancy-svc",
                    boom,
                )
            assertThatThrownBy { failing.handle(record) }.hasMessageContaining("outbox refused")
            assertThat(rows(ref)).isZero()

            env.withTenant { production ->
                val api = workspaces(env)
                val wsRef =
                    api
                        .createWorkspace(UUID.randomUUID(), CreateWorkspaceRequest(production.value.toString(), "uat"))
                        .body!!
                        .workspaceRef
                val store = ctx.getBean(WorkspaceStore::class.java)
                assertThatThrownBy { ArchiveWorkspace(tx, store, boom).handle(wsRef) }
                    .hasMessageContaining("outbox refused")
                assertThat(retired(wsRef)).isZero()
                val failingCreate = CreateWorkspace(tenants, tx, store, boom)
                assertThatThrownBy { failingCreate.handle(production.value.toString(), "other") }
                    .hasMessageContaining("outbox refused")
                assertThat(rows(TenantRefs.refOfWorkspace(production.value.toString(), "other"))).isZero()
            }
        }
    }
}
