package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.http.ApiRefusal
import com.dynokore.gst.tenancy.adapters.http.EntitiesController
import com.dynokore.gst.tenancy.adapters.http.TenancyRefusal
import com.dynokore.gst.tenancy.adapters.http.TenantsController
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.SHORT_CODE
import com.dynokore.gst.tenancy.application.structure.StructureCommands
import com.dynokore.gst.tenancy.contract.model.CreateEntityRequest
import com.dynokore.gst.tenancy.contract.model.CreateGroupRequest
import com.dynokore.gst.tenancy.contract.model.CreateTenantRequest
import com.dynokore.gst.tenancy.contract.model.EntityResponse
import com.dynokore.gst.tenancy.contract.model.GroupResponse
import com.dynokore.gst.tenancy.contract.model.MoveBusinessUnitRequest
import com.dynokore.gst.tenancy.contract.model.RegisterBusinessUnitRequest
import com.dynokore.gst.tenancy.contract.model.UpdateBusinessUnitRequest
import com.dynokore.gst.tenancy.domain.TenantRefs
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.TenancyErrorCode
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import java.util.UUID

/**
 * The operations createTenant, createGroup, createEntity, registerBusinessUnit, moveBusinessUnit, updateBusinessUnit
 * and getEntityStructure through the controllers of the real application context over PostgreSQL with forced
 * row-level security: the tenancy rows, the short-code rules (INV-REQ-850 item 7) and the events they publish.
 */
class OperationsTest {
    private class Env(
        val context: AssertableWebApplicationContext,
    ) {
        val tenantsApi: TenantsController = context.getBean(TenantsController::class.java)
        val entitiesApi: EntitiesController = context.getBean(EntitiesController::class.java)
        val commands: StructureCommands = context.getBean(StructureCommands::class.java)
        val tenants: TenantContext = context.getBean(TenantContext::class.java)
        val tx: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        val captured: CapturedEvents = context.getBean(CapturedEvents::class.java)

        fun <T> inTenant(
            tenant: TenantId,
            block: () -> T,
        ): T = tenants.bind(tenant, "user:test") { block() }

        fun newTenant(): TenantId {
            val contract = "C-${UUID.randomUUID()}"
            val ref = TenantRefs.refOfContract(contract)
            tenantsApi.createTenant(UUID.randomUUID(), CreateTenantRequest(contract, "Tenant $contract", "PLAN-A", ref))
            return TenantRefs.idOf(ref)
        }

        fun group(
            tenant: TenantId,
            gstn: String? = "M2-GSTN",
        ): GroupResponse =
            inTenant(tenant) {
                entitiesApi
                    .createGroup(
                        UUID.randomUUID(),
                        CreateGroupRequest(
                            tenant.value.toString(),
                            "Group ${UUID.randomUUID()}",
                            CreateGroupRequest.GroupKind.GST_GROUP,
                            gstn,
                        ),
                    ).body!!
            }

        fun entity(
            tenant: TenantId,
            group: GroupResponse,
            uen: String?,
            code: String? = null,
        ): EntityResponse =
            inTenant(tenant) {
                entitiesApi
                    .createEntity(
                        UUID.randomUUID(),
                        CreateEntityRequest(
                            tenant.value.toString(),
                            group.groupRef,
                            "Entity ${UUID.randomUUID()}",
                            CreateEntityRequest.Constitution.PRIVATE_LIMITED,
                            uen,
                            null,
                            code,
                        ),
                    ).body!!
            }
    }

    private fun run(block: (Env) -> Unit) {
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            block(Env(context))
        }
    }

    private fun tenancyRefusal(block: () -> Unit): TenancyErrorCode {
        val thrown = runCatching(block).exceptionOrNull()
        assertThat(thrown).isInstanceOf(TenancyRefusal::class.java)
        return (thrown as TenancyRefusal).code
    }

    private fun refusal(block: () -> Unit): ClosedErrorCode {
        val thrown = runCatching(block).exceptionOrNull()
        assertThat(thrown).isInstanceOf(ApiRefusal::class.java)
        return (thrown as ApiRefusal).code
    }

    private fun storedRegionOf(contract: String): List<String?> =
        BoundaryFixture.asSuperuser { connection ->
            connection
                .prepareStatement("select data_region from tenancy.tenant where contract_reference = ?")
                .use { statement ->
                    statement.setString(1, contract)
                    statement.executeQuery().use { rows ->
                        val values = mutableListOf<String?>()
                        while (rows.next()) values.add(rows.getString(1))
                        values
                    }
                }
        }

    private fun regionRows(contract: String): Int =
        BoundaryFixture.asSuperuser { connection ->
            connection
                .prepareStatement("select count(*) from tenancy.tenant where contract_reference = ?")
                .use { statement ->
                    statement.setString(1, contract)
                    statement.executeQuery().use { rows ->
                        rows.next()
                        rows.getInt(1)
                    }
                }
        }

    @Test
    fun `INV-AC-1331 INV-AC-8572 createTenant with dataRegion SG answers it and stores it`() {
        run { env ->
            val contract = "C-${UUID.randomUUID()}"
            val response =
                env.tenantsApi.createTenant(
                    UUID.randomUUID(),
                    CreateTenantRequest(contract, "Tenant", "PLAN-A", dataRegion = "SG"),
                )
            assertThat(response.statusCode.value()).isEqualTo(201)
            assertThat(response.body!!.dataRegion).isEqualTo("SG")
            assertThat(storedRegionOf(contract)).containsExactly("SG")
        }
    }

    @Test
    fun `createTenant without dataRegion answers none and stores NULL`() {
        run { env ->
            val contract = "C-${UUID.randomUUID()}"
            val response =
                env.tenantsApi.createTenant(
                    UUID.randomUUID(),
                    CreateTenantRequest(contract, "Tenant", "PLAN-A"),
                )
            assertThat(response.statusCode.value()).isEqualTo(201)
            assertThat(response.body!!.dataRegion).isNull()
            assertThat(storedRegionOf(contract)).containsExactly(null as String?)
        }
    }

    @Test
    fun `createTenant stores a dataRegion of 64 characters and refuses one of 65 with no row`() {
        run { env ->
            val ok = "C-${UUID.randomUUID()}"
            val region64 = "r".repeat(64)
            val stored =
                env.tenantsApi.createTenant(
                    UUID.randomUUID(),
                    CreateTenantRequest(ok, "Tenant", "PLAN-A", dataRegion = region64),
                )
            assertThat(stored.body!!.dataRegion).isEqualTo(region64)
            assertThat(storedRegionOf(ok)).containsExactly(region64)
            val bad = "C-${UUID.randomUUID()}"
            assertThat(
                refusal {
                    env.tenantsApi.createTenant(
                        UUID.randomUUID(),
                        CreateTenantRequest(bad, "Tenant", "PLAN-A", dataRegion = "r".repeat(65)),
                    )
                },
            ).isEqualTo(ClosedErrorCode.CONTRACT_VIOLATION)
            assertThat(regionRows(bad)).isZero()
        }
    }

    @Test
    fun `createTenant repeated with another dataRegion returns the original body and keeps the stored region`() {
        run { env ->
            val contract = "C-${UUID.randomUUID()}"
            val first =
                env.tenantsApi.createTenant(
                    UUID.randomUUID(),
                    CreateTenantRequest(contract, "Tenant", "PLAN-A", dataRegion = "SG"),
                )
            val again =
                env.tenantsApi.createTenant(
                    UUID.randomUUID(),
                    CreateTenantRequest(contract, "Tenant", "PLAN-A", dataRegion = "EU"),
                )
            assertThat(again.body).isEqualTo(first.body)
            assertThat(again.body!!.dataRegion).isEqualTo("SG")
            assertThat(storedRegionOf(contract)).containsExactly("SG")
        }
    }

    @Test
    fun `createTenant records the tenant once and a repeat of the same contract returns it`() {
        run { env ->
            val contract = "C-${UUID.randomUUID()}"
            val request = CreateTenantRequest(contract, "Tenant", "PLAN-A")
            val first = env.tenantsApi.createTenant(UUID.randomUUID(), request)
            val again = env.tenantsApi.createTenant(UUID.randomUUID(), request)
            assertThat(first.statusCode.value()).isEqualTo(201)
            assertThat(again.body).isEqualTo(first.body)
            assertThat(first.body!!.tenantRef).isEqualTo(TenantRefs.refOfContract(contract))
            val other =
                request.copy(
                    legalName = "Another",
                    contractReference = contract,
                    tenantRef = UUID.randomUUID().toString(),
                )
            assertThat(
                tenancyRefusal { env.tenantsApi.createTenant(UUID.randomUUID(), other) },
            ).isEqualTo(TenancyErrorCode.TENANT_NOT_CREATED)
        }
    }

    @Test
    fun `createEntity defaults the code to the UEN and refuses a duplicate, an 11-character and a lower-case code`() {
        run { env ->
            val tenant = env.newTenant()
            val group = env.group(tenant)
            val first = env.entity(tenant, group, "201912345A")
            assertThat(first.node!!.code).isEqualTo("201912345A")
            assertThat(first.lifecycleState).isEqualTo(EntityResponse.LifecycleState.DRAFT)
            val duplicate = tenancyRefusal { env.entity(tenant, group, "201912345A") }
            assertThat(duplicate).isEqualTo(TenancyErrorCode.SHORT_CODE_ALREADY_USED)
            assertThat(env.entity(tenant, group, "201912345A", "ALT1").node!!.code).isEqualTo("ALT1")
            assertThat(
                refusal { env.entity(tenant, group, null, "ABCDEFGHIJK") },
            ).isEqualTo(ClosedErrorCode.CONTRACT_VIOLATION)
            assertThat(refusal { env.entity(tenant, group, null, "abc") }).isEqualTo(ClosedErrorCode.CONTRACT_VIOLATION)
            assertThat(refusal { env.entity(tenant, group, null, null) }).isEqualTo(ClosedErrorCode.CONTRACT_VIOLATION)
        }
    }

    @Test
    fun `a code is unique within the tenant only, and a business unit code is entered at registration`() {
        run { env ->
            val tenantA = env.newTenant()
            val tenantB = env.newTenant()
            val entityA = env.entity(tenantA, env.group(tenantA), "SHARED1")
            val entityB = env.entity(tenantB, env.group(tenantB), "SHARED1")
            assertThat(entityA.node!!.code).isEqualTo(entityB.node!!.code)
            env.inTenant(tenantA) {
                val unit = RegisterBusinessUnitRequest(tenantA.value.toString(), entityA.entityRef, "Outlet", "BU1")
                assertThat(
                    env.entitiesApi
                        .registerBusinessUnit(UUID.randomUUID(), unit)
                        .body!!
                        .code,
                ).isEqualTo("BU1")
                val clash = unit.copy(name = "Second", code = "SHARED1")
                assertThat(
                    tenancyRefusal {
                        env.entitiesApi.registerBusinessUnit(UUID.randomUUID(), clash)
                    },
                ).isEqualTo(TenancyErrorCode.SHORT_CODE_ALREADY_USED)
            }
        }
    }

    @Test
    fun `a code already used in an aggregate number cannot be changed`() {
        run { env ->
            val tenant = env.newTenant()
            val entity = env.entity(tenant, env.group(tenant), "FIXED1")
            // the used-in-aggregate fact comes from a fixture until aggregation-svc publishes it (INV-REQ-436)
            BoundaryFixture.asSuperuser { connection ->
                connection.createStatement().use {
                    it.execute(
                        "UPDATE tenancy.short_code SET used_in_aggregate_number = true " +
                            "WHERE code = 'FIXED1' AND tenant_id = '${tenant.value}'",
                    )
                }
            }
            val outcome = env.inTenant(tenant) { env.commands.changeEntityCode(tenant, entity.entityRef, "FIXED2") }
            assertThat(outcome.toString()).contains("USED_IN_AGGREGATE_NUMBER")
            assertThat(
                env.inTenant(tenant) {
                    env.tx.inTransaction {
                        env.dsl.fetchCount(
                            SHORT_CODE,
                            SHORT_CODE.TENANT_ID.eq(tenant.value).and(SHORT_CODE.CODE.eq("FIXED1")),
                        )
                    }
                },
            ).isEqualTo(1)
        }
    }

    @Test
    fun `a business unit code used in an aggregate number is refused with TNC-003`() {
        run { env ->
            val tenant = env.newTenant()
            val entity = env.entity(tenant, env.group(tenant), "HOST1")
            env.inTenant(tenant) {
                val unit =
                    env.entitiesApi
                        .registerBusinessUnit(
                            UUID.randomUUID(),
                            RegisterBusinessUnitRequest(tenant.value.toString(), entity.entityRef, "Outlet", "FIXBU1"),
                        ).body!!
                BoundaryFixture.asSuperuser { connection ->
                    connection.createStatement().use {
                        it.execute(
                            "UPDATE tenancy.short_code SET used_in_aggregate_number = true " +
                                "WHERE code = 'FIXBU1' AND tenant_id = '${tenant.value}'",
                        )
                    }
                }
                val code =
                    tenancyRefusal {
                        env.entitiesApi.updateBusinessUnit(
                            unit.businessUnitRef,
                            UUID.randomUUID(),
                            UpdateBusinessUnitRequest("Outlet", "FIXBU2"),
                        )
                    }
                assertThat(code).isEqualTo(TenancyErrorCode.SHORT_CODE_FIXED)
            }
        }
    }

    @Test
    fun `INV-AC-1896 a business unit moved to another entity is published and the structure reads back`() {
        run { env ->
            val tenant = env.newTenant()
            val group = env.group(tenant)
            val from = env.entity(tenant, group, "FROM1")
            val to = env.entity(tenant, group, "TO1")
            env.inTenant(tenant) {
                val unit =
                    env.entitiesApi
                        .registerBusinessUnit(
                            UUID.randomUUID(),
                            RegisterBusinessUnitRequest(tenant.value.toString(), from.entityRef, "Outlet", "OUT1"),
                        ).body!!
                val before = env.captured.envelopes.size
                val moved =
                    env.entitiesApi
                        .moveBusinessUnit(
                            unit.businessUnitRef,
                            UUID.randomUUID(),
                            MoveBusinessUnitRequest(to.entityRef),
                        ).body!!
                assertThat(moved.entityRef).isEqualTo(to.entityRef)
                assertThat(env.captured.envelopes.size).isEqualTo(before + 1)
                val renamed =
                    env.entitiesApi
                        .updateBusinessUnit(
                            unit.businessUnitRef,
                            UUID.randomUUID(),
                            UpdateBusinessUnitRequest("Outlet 2", "OUT2"),
                        ).body!!
                assertThat(renamed.code).isEqualTo("OUT2")
                assertThat(env.captured.envelopes.size).isEqualTo(before + 2)
                val read = env.entitiesApi.getEntityStructure(to.entityRef).body!!
                assertThat(read.node!!.structureVersion).isGreaterThan(0)
            }
        }
    }

    @Test
    fun `INV-AC-1814 INV-AC-346 INV-AC-347 a group GSTN and the member UENs are served by getEntityStructure`() {
        run { env ->
            val tenant = env.newTenant()
            val group = env.group(tenant, "GSTN-G1")
            val member = env.entity(tenant, group, "UENM0001")
            val read = env.inTenant(tenant) { env.entitiesApi.getEntityStructure(member.entityRef).body!! }
            assertThat(read.node!!.uen).isEqualTo("UENM0001")
            assertThat(group.gstn).isEqualTo("GSTN-G1")
            val stranger = env.newTenant()
            val code = env.inTenant(stranger) { refusal { env.entitiesApi.getEntityStructure(member.entityRef) } }
            assertThat(code).isEqualTo(ClosedErrorCode.NOT_FOUND)
        }
    }
}
