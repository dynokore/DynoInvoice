package com.dynokore.gst.tenancy.adapters.policy

import com.dynokore.gst.tenancy.adapters.persistence.JooqDespatchFactStore
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.ENTITY
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.ENTITY_STATUS
import com.dynokore.platform.configresolver.BackdatingFloor
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.configresolver.SettingTarget
import com.dynokore.platform.configresolver.VariationPointKey
import org.jooq.DSLContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.LocalDate

/**
 * The [BackdatingFloor] of tenancy-svc (INV-Q-16675 option A, INV-Q-2977, INV-Q-3140, INV-AC-16774). For the key of
 * [TenancyVariationPoints.NON_BUSINESS_DAY_RULE] and a target of level ENTITY or CLIENT_ENTITY it answers the
 * `obligation_start_date` of the OBLIGATION_START row with the greatest `effective_from` of the entity named by
 * `SettingTarget.entity` (the entity ref), unless the replica of SubmissionDespatched facts holds a fact for (tenant,
 * entity) (the stricter reading "nothing transmitted for this entity at all"). It
 * answers null for every other key and level, an unknown entity, an entity without that row, a missing tenant and any
 * exception: it fails closed and never answers a default date.
 */
class TenancyBackdatingFloor(
    private val dsl: DSLContext,
) : BackdatingFloor {
    private val facts = JooqDespatchFactStore(dsl)

    @Suppress("TooGenericExceptionCaught") // fail closed: a read that throws must refuse back-dating, never open it
    override fun earliest(
        target: SettingTarget,
        key: VariationPointKey,
    ): LocalDate? =
        try {
            floor(target, key)
        } catch (e: Exception) {
            log.warn("the backdating floor failed closed: {}", e.javaClass.name)
            null
        }

    private fun floor(
        target: SettingTarget,
        key: VariationPointKey,
    ): LocalDate? {
        val tenant = target.tenant
        val entityRef = target.entity
        val asked = key == TenancyVariationPoints.NON_BUSINESS_DAY_RULE && target.ref.level in ENTITY_LEVELS
        val named = tenant != null && entityRef != null
        return if (!asked || !named || facts.hasFactFor(tenant, entityRef)) {
            null
        } else {
            dsl
                .select(ENTITY_STATUS.OBLIGATION_START_DATE)
                .from(ENTITY_STATUS)
                .join(ENTITY)
                .on(ENTITY.TENANT_ID.eq(ENTITY_STATUS.TENANT_ID).and(ENTITY.ID.eq(ENTITY_STATUS.ENTITY_ID)))
                .where(ENTITY_STATUS.TENANT_ID.eq(tenant.value))
                .and(ENTITY.REF.eq(entityRef))
                .and(ENTITY_STATUS.STATUS_KIND.eq(OBLIGATION_START))
                .orderBy(ENTITY_STATUS.EFFECTIVE_FROM.desc())
                .limit(1)
                .fetchOne(ENTITY_STATUS.OBLIGATION_START_DATE)
        }
    }

    private companion object {
        const val OBLIGATION_START = "OBLIGATION_START"
        val ENTITY_LEVELS = setOf(ScopeLevel.ENTITY, ScopeLevel.CLIENT_ENTITY)
        val log: Logger = LoggerFactory.getLogger(TenancyBackdatingFloor::class.java)
    }
}
