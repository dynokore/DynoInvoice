package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.persistence.JooqDespatchFactStore
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.ENTITY
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.ENTITY_STATUS
import com.dynokore.gst.tenancy.adapters.policy.TenancyBackdatingFloor
import com.dynokore.platform.configresolver.BackdatingFloor
import com.dynokore.platform.configresolver.PolicyRefusal
import com.dynokore.platform.configresolver.ScopeLevel
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * INV-AC-16774 through the service boundary and the real tables: with the fixture stand-in switched off the Spring
 * context resolves exactly one [BackdatingFloor], the bean of [BackdatingFloorConfiguration], and `setPolicy` of VP-62
 * with a back-dated first value is accepted only while no despatch fact exists for the entity.
 */
internal class BackdatingFloorWiringTest : Vp62TestSupport() {
    private val floorStart = day("2026-01-01")

    /** The entity's OBLIGATION_START row with the greatest `effective_from`, dated before the effective date used. */
    private fun Setup.openFloor() =
        period.env.inTenant(tenant) {
            period.env.inTx {
                val dsl = period.env.context.getBean(DSLContext::class.java)
                dsl
                    .insertInto(ENTITY_STATUS)
                    .set(ENTITY_STATUS.ID, UUID.randomUUID())
                    .set(ENTITY_STATUS.TENANT_ID, tenant.value)
                    .set(
                        ENTITY_STATUS.ENTITY_ID,
                        dsl.select(ENTITY.ID).from(ENTITY).where(ENTITY.REF.eq(entity.entityRef)),
                    ).set(ENTITY_STATUS.STATUS_KIND, "OBLIGATION_START")
                    .set(ENTITY_STATUS.EFFECTIVE_FROM, TODAY.plusDays(1))
                    .set(ENTITY_STATUS.OBLIGATION_START_DATE, floorStart)
                    .set(ENTITY_STATUS.OBLIGATION_START_BASIS, "MANDATORY_DATE")
                    .set(ENTITY_STATUS.RECORDED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                    .execute()
            }
        }

    private fun Setup.recordFact(entityRef: String) =
        period.env.inTenant(tenant) {
            period.env.inTx {
                val dsl = period.env.context.getBean(DSLContext::class.java)
                val at = Instant.parse("2026-11-02T01:00:00Z")
                JooqDespatchFactStore(dsl).keepEntityFact(tenant, entityRef, at, null)
            }
        }

    private fun withService(block: (Setup) -> Unit) {
        BoundaryFixture
            .runner()
            .withPropertyValues("test.backdating-stub=false")
            .run { context ->
                context.startupFailure?.let { throw it }
                val env = ProfileEnv(context)
                env.withGroup(1, DIVISIONAL) { tenant, group, members ->
                    val period = PeriodEnv(env)
                    period.calendar.pack = saturdayPack()
                    val entity = members.single()
                    period.register(entity, "2026-01-01")
                    block(Setup(period, tenant, entity, group.groupRef))
                }
            }
    }

    @Test
    fun `INV-AC-16774 the context resolves exactly one BackdatingFloor bean and it is the tenancy floor`() {
        withService { s ->
            val beans =
                s.period.env.context
                    .getBeansOfType(BackdatingFloor::class.java)
            assertThat(beans).hasSize(1)
            assertThat(beans.values.single()).isInstanceOf(TenancyBackdatingFloor::class.java)
        }
    }

    @Test
    fun `INV-AC-16774 a back-dated first value is refused below the real start and accepted at an earlier floor`() {
        withService { s ->
            val scope = s.scope()
            val status = s.acknowledge(scope, ScopeLevel.ENTITY, "user:approver", "tax.approver")
            val refused = s.refusal { s.set(scope, ScopeLevel.ENTITY, status.relaxation, day("2026-09-01")) }
            assertThat(refused).isEqualTo(PolicyRefusal.BACKDATING_REFUSED)
            s.openFloor()
            s.set(scope, ScopeLevel.ENTITY, status.relaxation, day("2026-09-01"))
            assertThat(s.policyChanges()).isNotEmpty()
        }
    }

    @Test
    fun `INV-AC-16774 a back-dated first value is refused once a despatch fact exists for the entity`() {
        withService { s ->
            val scope = s.scope()
            val status = s.acknowledge(scope, ScopeLevel.ENTITY, "user:approver", "tax.approver")
            s.openFloor()
            s.recordFact(s.entity.entityRef)
            val refusal = s.refusal { s.set(scope, ScopeLevel.ENTITY, status.relaxation, day("2026-09-01")) }
            assertThat(refusal).isEqualTo(PolicyRefusal.BACKDATING_REFUSED)
            assertThat(s.policyChanges()).isEmpty()
        }
    }

    @Test
    fun `INV-AC-16774 a fact of another entity of the tenant does not refuse the back-dated first value`() {
        withService { s ->
            val scope = s.scope()
            val status = s.acknowledge(scope, ScopeLevel.ENTITY, "user:approver", "tax.approver")
            s.openFloor()
            s.recordFact("another-entity-ref")
            s.set(scope, ScopeLevel.ENTITY, status.relaxation, day("2026-09-01"))
            assertThat(s.policyChanges()).isNotEmpty()
        }
    }
}
