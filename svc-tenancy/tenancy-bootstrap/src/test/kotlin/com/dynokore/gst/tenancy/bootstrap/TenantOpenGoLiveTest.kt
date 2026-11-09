package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.http.TenancyRefusal
import com.dynokore.gst.tenancy.application.offboarding.OffboardOutcome
import com.dynokore.gst.tenancy.application.offboarding.OffboardRequest
import com.dynokore.gst.tenancy.application.offboarding.OffboardTenant
import com.dynokore.gst.tenancy.contract.model.CreateTenantRequest
import com.dynokore.gst.tenancy.contract.model.EntityResponse
import com.dynokore.gst.tenancy.contract.model.OpenTenantRequest
import com.dynokore.gst.tenancy.contract.model.TenantStateResponse
import com.dynokore.gst.tenancy.domain.OffboardingScope
import com.dynokore.gst.tenancy.domain.RetentionCustodyHolder
import com.dynokore.gst.tenancy.domain.TenantRefs
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.http.TenancyErrorCode
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * INV-AC-8139 at the service boundary (INV-Q-8097 B): the tenant state SCHEDULED, OPEN, CLOSED and the goLive refusal
 * naming `tenant not open`. The states are compared by equality with the stored text.
 */
class TenantOpenGoLiveTest {
    private fun refOf(tenant: TenantId): String =
        BoundaryFixture.asSuperuser { c ->
            c.prepareStatement("select ref from tenancy.tenant where tenant_id = ?").use { s ->
                s.setObject(1, tenant.value)
                s.executeQuery().use {
                    it.next()
                    it.getString(1)
                }
            }
        }

    private fun open(
        l: Lab,
        tenant: TenantId,
    ) = l.env.tenantsApi.openTenant(refOf(tenant), UUID.randomUUID(), OpenTenantRequest("SCH-1"))

    private fun wholeTenantOffboarding(
        l: Lab,
        tenant: TenantId,
        scope: OffboardingScope = OffboardingScope.TENANT,
        entity: EntityResponse? = null,
    ): OffboardOutcome =
        l.env.inTenant(tenant) {
            val holder = RetentionCustodyHolder("Ledger Custody Pte Ltd", "custody@ledger.example")
            val use = l.env.context.getBean(OffboardTenant::class.java)
            if (scope == OffboardingScope.TENANT) {
                use.forWholeTenant(
                    tenant.value.toString(),
                    UUID.randomUUID().toString(),
                    OffboardRequest(scope, null, holder),
                )
            } else {
                use.byPerson(
                    tenant.value.toString(),
                    ADMIN,
                    UUID.randomUUID().toString(),
                    OffboardRequest(scope, entity!!.entityRef, holder, secondConfirmationBy = APPROVER),
                )
            }
        }

    @Test
    @Suppress("LongMethod") // one scenario, read top to bottom as the acceptance criterion states it
    fun `INV-AC-8139 the scenario - T1 SCHEDULED is refused, T2 OPEN goes live, then T1 opens, goes live and closes`() {
        lifecycleRun { l ->
            // T2 is opened through openTenant; T1 is not.
            val t2 =
                l.env.withEntity { tenant, entity ->
                    lifecycleReady(l, entity, openTenant = false)
                    tenant to entity
                }
            assertThat(open(l, t2.first).body)
                .isEqualTo(TenantStateResponse(refOf(t2.first), TenantStateResponse.State.OPEN))
            l.env.withGroup(2) { t1, _, entities ->
                val ready = entities[0]
                val configuring = entities[1]
                // createEntity (two entities above) and certifySandbox (lifecycleReady) while SCHEDULED are accepted.
                lifecycleReady(l, ready, openTenant = false)
                assertThat(storedTenantState(t1)).isEqualTo("SCHEDULED")

                val eventsBefore = profileEventRows(ready).size
                val first = callGoLive(l.env, ready)
                assertThat(listOf(first.status, first.code)).isEqualTo(listOf(422, "POL-014"))
                assertThat(first.detail).contains("tenant not open")
                assertThat(storedState(ready)).isNotEqualTo("LIVE")
                assertThat(profileEventRows(ready)).hasSize(eventsBefore)
                assertThat(storedTenantState(t1)).isEqualTo("SCHEDULED")

                // an entity outstanding on another item and on the tenant: the detail names both
                l.elections.usualPeople(configuring)
                val both = callGoLive(l.env, configuring)
                assertThat(both.status).isEqualTo(422)
                assertThat(both.detail).contains("tenant not open").contains("sandbox certification")

                // an OPEN second tenant must not let the entity of the SCHEDULED tenant go live (look-alike)
                assertThat(storedTenantState(t2.first)).isEqualTo("OPEN")
                assertThat(callGoLive(l.env, ready).status).isEqualTo(422)

                // T2's entity goes live
                l.env.inTenant(t2.first) { assertThat(callGoLive(l.env, t2.second).status).isEqualTo(200) }

                // the opening step, then T1's entity goes live
                assertThat(open(l, t1).body!!.state).isEqualTo(TenantStateResponse.State.OPEN)
                l.env.inTenant(t1) { assertThat(callGoLive(l.env, ready).status).isEqualTo(200) }
                assertThat(storedState(ready)).isEqualTo("LIVE")
                // a repeated goLive on a live entity of an OPEN tenant is still a no-op
                l.env.inTenant(t1) { assertThat(callGoLive(l.env, ready).status).isEqualTo(200) }

                // a COMPLETED offboarding of scope ENTITY must leave the tenant OPEN (look-alike)
                assertThat(wholeTenantOffboarding(l, t1, OffboardingScope.ENTITY, ready))
                    .isInstanceOf(OffboardOutcome.Accepted::class.java)
                assertThat(storedTenantState(t1)).isEqualTo("OPEN")

                // the completed whole-tenant offboarding closes T1 and no other tenant
                assertThat(wholeTenantOffboarding(l, t1)).isInstanceOf(OffboardOutcome.Accepted::class.java)
                assertThat(storedTenantState(t1)).isEqualTo("CLOSED")
                assertThat(storedTenantState(t2.first)).isEqualTo("OPEN")
            }
        }
    }

    @Test
    fun `INV-AC-8139 the look-alike OPENED written by hand is still refused (equality with OPEN)`() {
        lifecycleRun { l ->
            l.env.withEntity { tenant, entity ->
                lifecycleReady(l, entity, openTenant = false)
                for (state in listOf("OPENED", "open", "OPEN ", "")) {
                    setTenantState(tenant, state)
                    val answer = callGoLive(l.env, entity)
                    assertThat(answer.status).describedAs("state '$state'").isEqualTo(422)
                    assertThat(answer.detail).contains("tenant not open")
                }
                assertThat(storedState(entity)).isNotEqualTo("LIVE")
            }
        }
    }

    @Test
    fun `INV-AC-8139 createTenant stores SCHEDULED, openTenant opens once and refuses CLOSED with 409`() {
        lifecycleRun { l ->
            val contract = "C-${UUID.randomUUID()}"
            val ref = TenantRefs.refOfContract(contract)
            val otherContract = "C-${UUID.randomUUID()}"
            val otherRef = TenantRefs.refOfContract(otherContract)
            for ((c, r) in listOf(contract to ref, otherContract to otherRef)) {
                l.env.tenantsApi.createTenant(UUID.randomUUID(), CreateTenantRequest(c, "Tenant $c", "PLAN-A", r))
            }
            val id = TenantRefs.idOf(ref)
            val other = TenantRefs.idOf(otherRef)
            assertThat(storedTenantState(id)).isEqualTo("SCHEDULED")

            // openTenant for another tenantRef leaves this tenant SCHEDULED
            val opened = l.env.tenantsApi.openTenant(otherRef, UUID.randomUUID(), OpenTenantRequest("SCH-2"))
            assertThat(opened.statusCode.value()).isEqualTo(200)
            assertThat(storedTenantState(id)).isEqualTo("SCHEDULED")
            assertThat(storedTenantState(other)).isEqualTo("OPEN")

            assertThat(
                l.env.tenantsApi
                    .openTenant(ref, UUID.randomUUID(), OpenTenantRequest("SCH-1"))
                    .body!!
                    .state,
            ).isEqualTo(TenantStateResponse.State.OPEN)
            assertThat(
                l.env.tenantsApi
                    .openTenant(ref, UUID.randomUUID(), OpenTenantRequest("SCH-1"))
                    .body!!
                    .state,
            ).isEqualTo(TenantStateResponse.State.OPEN)
            assertThat(storedTenantState(id)).isEqualTo("OPEN")

            setTenantState(id, "CLOSED")
            val refused =
                runCatching { l.env.tenantsApi.openTenant(ref, UUID.randomUUID(), OpenTenantRequest("SCH-1")) }
                    .exceptionOrNull()
            assertThat(refused).isInstanceOf(TenancyRefusal::class.java)
            assertThat(storedTenantState(id)).isEqualTo("CLOSED")
        }
    }

    private fun rowCount(table: String): Long =
        BoundaryFixture.asSuperuser { c ->
            c.prepareStatement("select count(*) from $table").use { s ->
                s.executeQuery().use {
                    it.next()
                    it.getLong(1)
                }
            }
        }

    private fun refusalOfOpen(
        l: Lab,
        ref: String,
    ): TenancyRefusal =
        runCatching { l.env.tenantsApi.openTenant(ref, UUID.randomUUID(), OpenTenantRequest("SCH-1")) }
            .exceptionOrNull() as TenancyRefusal

    private fun assertRendersTnc012(refusal: TenancyRefusal) {
        // the pinned library carries the code
        assertThat(refusal.code).isEqualTo(TenancyErrorCode.TENANT_NOT_OPENABLE)
        assertThat(refusal.code.code).isEqualTo("TNC-012")
        assertThat(refusal.code.httpStatus).isEqualTo(409)
        val problem = Problems.renderTenancy(refusal.code, "corr-19380", refusal.detail)
        assertThat(problem.status).isEqualTo(409)
        assertThat(problem.body).contains("\"https://errors.invoice.internal/TNC-012\"")
        assertThat(problem.body).contains("TNC-012")
        // look-alikes: neither TNC-004 nor TNC-010 renders as the refusal
        assertThat(problem.body).doesNotContain("TNC-004").doesNotContain("TNC-010")
        assertThat(TenancyErrorCode.STATUS_RECORD_CONFLICT.code).isEqualTo("TNC-004")
        assertThat(Problems.renderTenancy(TenancyErrorCode.STATUS_RECORD_CONFLICT, "c", null).body)
            .doesNotContain("TNC-012")
    }

    @Test
    fun `INV-AC-19380 openTenant for a CLOSED tenant answers 409 TNC-012 and changes nothing`() {
        lifecycleRun { l ->
            val contract = "C-${UUID.randomUUID()}"
            val ref = TenantRefs.refOfContract(contract)
            l.env.tenantsApi.createTenant(
                UUID.randomUUID(),
                CreateTenantRequest(contract, "Tenant $contract", "PLAN-A", ref),
            )
            val id = TenantRefs.idOf(ref)
            // a SCHEDULED or OPEN tenant never answers 409
            assertThat(
                l.env.tenantsApi
                    .openTenant(ref, UUID.randomUUID(), OpenTenantRequest("SCH-1"))
                    .statusCode
                    .value(),
            ).isEqualTo(200)
            assertThat(
                l.env.tenantsApi
                    .openTenant(ref, UUID.randomUUID(), OpenTenantRequest("SCH-1"))
                    .statusCode
                    .value(),
            ).isEqualTo(200)

            setTenantState(id, "CLOSED")
            val tenants = rowCount("tenancy.tenant")
            val events = rowCount("tenancy.outbox")
            val refusal = refusalOfOpen(l, ref)

            assertRendersTnc012(refusal)

            // nothing changed: state, rows and events
            assertThat(storedTenantState(id)).isEqualTo("CLOSED")
            assertThat(rowCount("tenancy.tenant")).isEqualTo(tenants)
            assertThat(rowCount("tenancy.outbox")).isEqualTo(events)
        }
    }
}
