package com.dynokore.gst.tenancy.adapters.policy

import com.dynokore.gst.tenancy.adapters.persistence.DespatchTestDb
import com.dynokore.gst.tenancy.adapters.persistence.JooqDespatchFactStore
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.configresolver.ScopeRef
import com.dynokore.platform.configresolver.SettingTarget
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/**
 * INV-AC-16774: the BackdatingFloor of tenancy-svc answers the obligation start for a VP-62 target only when the entity
 * has no recorded despatch, and null in every other case.
 */
class TenancyBackdatingFloorTest {
    private val vp62 = TenancyVariationPoints.NON_BUSINESS_DAY_RULE
    private val start = LocalDate.of(2026, 1, 1)
    private val at = Instant.parse("2026-11-01T01:00:00Z")

    private fun target(
        tenant: TenantId,
        entity: String,
        level: ScopeLevel = ScopeLevel.ENTITY,
    ) = SettingTarget(tenant, ScopeRef(level, entity), entity)

    private fun earliest(
        tenant: TenantId,
        target: SettingTarget,
        key: VariationPointKey = vp62,
    ): LocalDate? = DespatchTestDb.bound(tenant) { dsl -> TenancyBackdatingFloor(dsl).earliest(target, key) }

    private fun fact(
        tenant: TenantId,
        entityRef: String,
    ) = DespatchTestDb.bound(tenant) { JooqDespatchFactStore(it).keepEntityFact(tenant, entityRef, at, null) }

    @Test
    fun `INV-AC-16774 the floor equals the obligation start date for an ENTITY target`() {
        val tenant = DespatchTestDb.tenant()
        val entity = DespatchTestDb.entity(tenant, start to start)
        earliest(tenant, target(tenant, entity)) shouldBe start
    }

    @Test
    fun `INV-AC-16774 the floor equals the obligation start date for a CLIENT_ENTITY target`() {
        val tenant = DespatchTestDb.tenant()
        val entity = DespatchTestDb.entity(tenant, start to start)
        earliest(tenant, target(tenant, entity, ScopeLevel.CLIENT_ENTITY)) shouldBe start
    }

    @Test
    fun `INV-AC-16774 the floor is the date of the OBLIGATION_START row with the greatest effective_from`() {
        val tenant = DespatchTestDb.tenant()
        val later = LocalDate.of(2026, 3, 1)
        val entity =
            DespatchTestDb.entity(
                tenant,
                start to start,
                LocalDate.of(2026, 6, 1) to later,
                LocalDate.of(2026, 2, 1) to start,
            )
        earliest(tenant, target(tenant, entity)) shouldBe later
    }

    @Test
    fun `INV-AC-16774 a fact for the entity makes the floor null`() {
        val tenant = DespatchTestDb.tenant()
        val entity = DespatchTestDb.entity(tenant, start to start)
        fact(tenant, entity)
        earliest(tenant, target(tenant, entity)) shouldBe null
        earliest(tenant, target(tenant, entity, ScopeLevel.CLIENT_ENTITY)) shouldBe null
    }

    @Test
    fun `INV-AC-16774 a fact of another entity or of another tenant for the same entity ref leaves the floor`() {
        val tenant = DespatchTestDb.tenant()
        val other = DespatchTestDb.tenant()
        val entity = DespatchTestDb.entity(tenant, start to start)
        fact(tenant, "e-other")
        fact(other, entity)
        earliest(tenant, target(tenant, entity)) shouldBe start
    }

    @Test
    fun `INV-AC-16774 the key VP-620 and every other key answer null`() {
        val tenant = DespatchTestDb.tenant()
        val entity = DespatchTestDb.entity(tenant, start to start)
        listOf("VP-620", "VP-61", "VP-162", "VP-12").forEach { key ->
            earliest(tenant, target(tenant, entity), VariationPointKey(key)) shouldBe null
        }
    }

    @Test
    fun `INV-AC-16774 the levels GROUP TENANT INSTALLATION FEED and AP_CONNECTION answer null`() {
        val tenant = DespatchTestDb.tenant()
        val entity = DespatchTestDb.entity(tenant, start to start)
        earliest(tenant, SettingTarget(tenant, ScopeRef(ScopeLevel.GROUP, "g-1"), null)) shouldBe null
        earliest(tenant, SettingTarget(tenant, ScopeRef(ScopeLevel.TENANT, tenant.toString()), null)) shouldBe null
        earliest(tenant, SettingTarget(null, ScopeRef(ScopeLevel.INSTALLATION, "i"), null)) shouldBe null
        earliest(tenant, SettingTarget(tenant, ScopeRef(ScopeLevel.FEED, "f-1"), entity)) shouldBe null
        earliest(tenant, SettingTarget(tenant, ScopeRef(ScopeLevel.AP_CONNECTION, "a-1"), entity)) shouldBe null
    }

    @Test
    fun `INV-AC-16774 an entity ref that only starts like E or differs in case answers null`() {
        val tenant = DespatchTestDb.tenant()
        val entity = DespatchTestDb.entity(tenant, start to start)
        listOf(entity.dropLast(1), entity + "0", "e", "E", entity.uppercase(), " $entity").forEach { ref ->
            earliest(tenant, target(tenant, ref)) shouldBe null
        }
        earliest(tenant, target(tenant, "e-unknown")) shouldBe null
    }

    @Test
    fun `INV-AC-16774 an entity without an OBLIGATION_START row answers null`() {
        val tenant = DespatchTestDb.tenant()
        val entity = DespatchTestDb.entity(tenant)
        earliest(tenant, target(tenant, entity)) shouldBe null
    }

    @Test
    fun `INV-AC-16774 an entity of another tenant answers null`() {
        val tenant = DespatchTestDb.tenant()
        val other = DespatchTestDb.tenant()
        val entity = DespatchTestDb.entity(other, start to start)
        earliest(tenant, target(tenant, entity)) shouldBe null
        // the session of the entity's own tenant sees the row, so only the target's tenant condition hides it
        earliest(other, target(tenant, entity)) shouldBe null
    }

    @Test
    fun `INV-AC-16774 a store that throws answers null and never a date`() {
        val tenant = DespatchTestDb.tenant()
        val entity = DespatchTestDb.entity(tenant, start to start)
        TenancyBackdatingFloor(DespatchTestDb.brokenDsl()).earliest(target(tenant, entity), vp62) shouldBe
            null
    }
}
