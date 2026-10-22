package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.http.ApiRefusal
import com.dynokore.gst.tenancy.adapters.http.EntitiesController
import com.dynokore.gst.tenancy.adapters.http.TenantsController
import com.dynokore.gst.tenancy.application.status.EntityLifecycleGuard
import com.dynokore.gst.tenancy.application.status.EntityStatusQueries
import com.dynokore.gst.tenancy.application.status.GoLiveStatusDecision
import com.dynokore.gst.tenancy.contract.model.CreateEntityRequest
import com.dynokore.gst.tenancy.contract.model.CreateGroupRequest
import com.dynokore.gst.tenancy.contract.model.CreateTenantRequest
import com.dynokore.gst.tenancy.contract.model.EntityResponse
import com.dynokore.gst.tenancy.contract.model.SetEntityStatusRequest
import com.dynokore.gst.tenancy.domain.ExcludedBusinessStatus
import com.dynokore.gst.tenancy.domain.StatusKind
import com.dynokore.gst.tenancy.domain.TenantRefs
import com.dynokore.platform.productruntime.ProductEventTypes
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID

private typealias Excl = SetEntityStatusRequest.ExcludedBusinessStatus

private fun day(text: String): LocalDate = LocalDate.parse(text)

/**
 * setEntityStatus (INV-REQ-322, INV-REQ-331, INV-REQ-333) and the entity-lifecycle status guard through the real
 * application context over PostgreSQL with forced row-level security. Test names carry the criterion key.
 */
class EntityStatusTest {
    private class Env(
        context: AssertableWebApplicationContext,
    ) {
        val tenantsApi: TenantsController = context.getBean(TenantsController::class.java)
        val api: EntitiesController = context.getBean(EntitiesController::class.java)
        val queries: EntityStatusQueries = context.getBean(EntityStatusQueries::class.java)
        val guard: EntityLifecycleGuard = context.getBean(EntityLifecycleGuard::class.java)
        val tenants: TenantContext = context.getBean(TenantContext::class.java)
        val captured: CapturedEvents = context.getBean(CapturedEvents::class.java)

        fun <T> inTenant(
            tenant: TenantId,
            block: () -> T,
        ): T = tenants.bind(tenant, "user:test") { block() }

        /** A tenant with one entity; [block] runs bound to the tenant. */
        fun <T> withEntity(block: (TenantId, EntityResponse) -> T): T {
            val contract = "C-${UUID.randomUUID()}"
            val ref = TenantRefs.refOfContract(contract)
            tenantsApi.createTenant(UUID.randomUUID(), CreateTenantRequest(contract, "Tenant $contract", "PLAN-A", ref))
            val tenant = TenantRefs.idOf(ref)
            return inTenant(tenant) {
                val group =
                    api
                        .createGroup(
                            UUID.randomUUID(),
                            CreateGroupRequest(
                                tenant.value.toString(),
                                "Group ${UUID.randomUUID()}",
                                CreateGroupRequest.GroupKind.GST_GROUP,
                                "GSTN-1",
                            ),
                        ).body!!
                val entity =
                    api
                        .createEntity(
                            UUID.randomUUID(),
                            CreateEntityRequest(
                                tenant.value.toString(),
                                group.groupRef,
                                "Entity ${UUID.randomUUID()}",
                                CreateEntityRequest.Constitution.PRIVATE_LIMITED,
                                "U${UUID.randomUUID().toString().take(8).uppercase()}",
                                null,
                                null,
                            ),
                        ).body!!
                block(tenant, entity)
            }
        }

        fun set(
            entity: EntityResponse,
            request: SetEntityStatusRequest,
        ) = api.setEntityStatus(entity.entityRef, UUID.randomUUID(), request)
    }

    private fun run(block: (Env) -> Unit) {
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            block(Env(context))
        }
    }

    private fun exempt(
        from: String,
        value: Boolean,
    ) = SetEntityStatusRequest(
        SetEntityStatusRequest.StatusKind.PARTIAL_EXEMPTION,
        day(from),
        partiallyExempt = value,
        basis = "partial exemption review",
    )

    private fun excluded(
        from: String,
        status: Excl,
    ) = SetEntityStatusRequest(
        SetEntityStatusRequest.StatusKind.EXCLUDED_BUSINESS,
        day(from),
        excludedBusinessStatus = status,
        basis = "determination",
        evidenceRef = "EVID-1",
    )

    private fun obligation(from: String) =
        SetEntityStatusRequest(
            SetEntityStatusRequest.StatusKind.OBLIGATION_START,
            day(from),
            obligationStartDate = day("2028-04-01"),
        )

    private fun profileEvents(env: Env) =
        env.captured.envelopes
            .filter { it.type == ProductEventTypes.ENTITY_PROFILE_CHANGED }
            .map { JsonMapper.builder().build().readTree(it.dataJson) }

    @Test
    fun `INV-AC-8584 statuses keep history, publish events and an excluded entity is refused go-live`() {
        run { env ->
            env.withEntity { tenant, entity ->
                val before = profileEvents(env).size
                env.set(entity, exempt("2026-07-01", true))
                env.set(entity, exempt("2026-10-01", false))
                env.set(entity, excluded("2026-07-01", Excl.OVR))
                env.set(entity, obligation("2026-07-01"))
                val events = profileEvents(env).drop(before)
                assertThat(events).hasSize(4)
                assertThat(events.map { it["changedAttributes"][0]["attribute"].asString() })
                    .containsExactly(
                        "partiallyExempt",
                        "partiallyExempt",
                        "excludedBusinessStatus",
                        "obligationStartDate",
                    )
                assertThat(events.all { it.has("effectiveDate") && it["node"].has("structureVersion") }).isTrue()
                assertThat(events[1]["changedAttributes"][0]["oldValue"].asString()).isEqualTo("true")
                assertThat(
                    env.queries.history(entity.entityRef, StatusKind.PARTIAL_EXEMPTION).map { it.partiallyExempt },
                ).containsExactly(true, false)
                val read =
                    env.api
                        .getEntityStructure(entity.entityRef)
                        .body!!
                        .node!!
                assertThat(read.profile.map { it.attribute }).contains("partiallyExempt", "excludedBusinessStatus")
                val decision = env.guard.checkStatus(entity.entityRef, day("2026-12-01"), false)
                assertThat(decision).isEqualTo(GoLiveStatusDecision.Refused(ExcludedBusinessStatus.OVR))
                assertThat(tenant).isNotNull
            }
        }
    }

    @Test
    fun `INV-AC-8585 INV-AC-1552 a status set from 1 July is read by transaction date and both values stay stored`() {
        run { env ->
            env.withEntity { _, entity ->
                env.set(entity, exempt("2026-01-01", false))
                env.set(entity, exempt("2026-07-01", true))
                val on30June = env.queries.inForceOn(entity.entityRef, StatusKind.PARTIAL_EXEMPTION, day("2026-06-30"))
                val on1July = env.queries.inForceOn(entity.entityRef, StatusKind.PARTIAL_EXEMPTION, day("2026-07-01"))
                assertThat(on30June!!.partiallyExempt).isFalse()
                assertThat(on1July!!.partiallyExempt).isTrue()
                assertThat(env.queries.history(entity.entityRef, StatusKind.PARTIAL_EXEMPTION)).hasSize(2)
                assertThat(env.queries.inForceOn(entity.entityRef, StatusKind.PARTIAL_EXEMPTION, day("2025-12-31")))
                    .isNull()
            }
        }
    }

    @Test
    fun `INV-AC-353 an excluded entity recorded with its evidence is refused go-live`() {
        run { env ->
            env.withEntity { _, entity ->
                val response = env.set(entity, excluded("2026-07-01", Excl.OVERSEAS_S33_1)).body!!
                assertThat(response.evidenceRef).isEqualTo("EVID-1")
                assertThat(response.basis).isEqualTo("determination")
                val decision = env.guard.checkStatus(entity.entityRef, day("2026-08-01"), false)
                assertThat(decision).isEqualTo(GoLiveStatusDecision.Refused(ExcludedBusinessStatus.OVERSEAS_S33_1))
                for (status in listOf(Excl.OVR, Excl.RC_ONLY)) {
                    env.withEntity { _, other ->
                        env.set(other, excluded("2026-07-01", status))
                        assertThat(env.guard.checkStatus(other.entityRef, day("2026-08-01"), false))
                            .isEqualTo(GoLiveStatusDecision.Refused(ExcludedBusinessStatus.valueOf(status.name)))
                    }
                }
            }
        }
    }

    @Test
    fun `INV-AC-354 a not excluded entity is in scope and the status gate does not refuse go-live`() {
        run { env ->
            env.withEntity { _, entity ->
                env.set(entity, excluded("2026-07-01", Excl.NOT_EXCLUDED))
                assertThat(env.guard.checkStatus(entity.entityRef, day("2026-08-01"), false))
                    .isEqualTo(GoLiveStatusDecision.Allowed(ExcludedBusinessStatus.NOT_EXCLUDED))
                assertThat(env.guard.checkStatus(entity.entityRef, day("2026-06-30"), false))
                    .isEqualTo(GoLiveStatusDecision.NotDetermined)
            }
        }
    }

    @Test
    fun `setEntityStatus repeats without a second event and refuses a request lacking its basis or evidence`() {
        run { env ->
            env.withEntity { _, entity ->
                val before = profileEvents(env).size
                env.set(entity, exempt("2026-07-01", true))
                env.set(entity, exempt("2026-07-01", true))
                assertThat(profileEvents(env).size).isEqualTo(before + 1)
                val noEvidence = excluded("2026-07-01", Excl.OVR).copy(evidenceRef = null)
                assertThatThrownBy { env.set(entity, noEvidence) }.isInstanceOf(ApiRefusal::class.java)
                val noBasis = exempt("2026-08-01", true).copy(basis = null)
                assertThatThrownBy { env.set(entity, noBasis) }.isInstanceOf(ApiRefusal::class.java)
                assertThatThrownBy {
                    env.api.setEntityStatus(
                        "no-such-entity",
                        UUID.randomUUID(),
                        exempt("2026-07-01", true),
                    )
                }.isInstanceOf(ApiRefusal::class.java)
            }
        }
    }
}
