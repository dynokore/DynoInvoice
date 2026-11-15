package com.dynokore.gst.rules.adapters.persistence

import com.dynokore.gst.rules.adapters.persistence.jooq.tables.references.ENTITY_PERIOD
import com.dynokore.gst.rules.adapters.persistence.jooq.tables.references.ENTITY_PERIOD_SET
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** One period record of an entity as tenancy-svc published it: the opaque reference and its dates. */
data class PeriodSpan(
    val periodRef: String,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
)

/**
 * The period records of the entities over `rules.entity_period_set` and `rules.entity_period` (INV-T-20226,
 * INV-REQ-1303), jOOQ only. The set of an entity is replaced as a whole and only by a strictly higher version, so a
 * redelivered or older event changes nothing (idempotent consumer by version). Row-level security hides every other
 * tenant's rows; the tenant is also named in every statement.
 */
class JooqPeriodRecordsStore(
    private val dsl: DSLContext,
) {
    /**
     * Replaces the whole set of [entityRef] of [tenant] by [spans] in the caller's open transaction (the inbox
     * consumer's) when [version] is strictly higher than the stored one, or no row exists. True when applied, false
     * (nothing changed) when [version] is lower or equal.
     */
    fun replace(
        tenant: TenantId,
        entityRef: String,
        version: Long,
        spans: List<PeriodSpan>,
        receivedAt: Instant,
    ): Boolean {
        val received = receivedAt.atOffset(ZoneOffset.UTC)
        // One statement decides: the row is written only when absent or lower, so two deliveries cannot both win.
        val applied =
            dsl
                .insertInto(ENTITY_PERIOD_SET)
                .set(ENTITY_PERIOD_SET.TENANT_ID, tenant.value)
                .set(ENTITY_PERIOD_SET.ENTITY_REF, entityRef)
                .set(ENTITY_PERIOD_SET.VERSION, version)
                .set(ENTITY_PERIOD_SET.RECEIVED_AT, received)
                .onConflict(ENTITY_PERIOD_SET.TENANT_ID, ENTITY_PERIOD_SET.ENTITY_REF)
                .doUpdate()
                .set(ENTITY_PERIOD_SET.VERSION, version)
                .set(ENTITY_PERIOD_SET.RECEIVED_AT, received)
                .where(ENTITY_PERIOD_SET.VERSION.lt(version))
                .execute()
        if (applied == 0) return false
        dsl
            .deleteFrom(ENTITY_PERIOD)
            .where(ENTITY_PERIOD.TENANT_ID.eq(tenant.value))
            .and(ENTITY_PERIOD.ENTITY_REF.eq(entityRef))
            .execute()
        for (span in spans) {
            dsl
                .insertInto(ENTITY_PERIOD)
                .set(ENTITY_PERIOD.TENANT_ID, tenant.value)
                .set(ENTITY_PERIOD.ENTITY_REF, entityRef)
                .set(ENTITY_PERIOD.PERIOD_REF, span.periodRef)
                .set(ENTITY_PERIOD.PERIOD_START, span.periodStart)
                .set(ENTITY_PERIOD.PERIOD_END, span.periodEnd)
                .execute()
        }
        return true
    }

    /** The stored spans of [entityRef] of [tenant], or null when no row exists for the entity. */
    fun spans(
        tenant: TenantId,
        entityRef: String,
    ): List<PeriodSpan>? {
        val exists =
            dsl.fetchExists(
                dsl
                    .selectOne()
                    .from(ENTITY_PERIOD_SET)
                    .where(ENTITY_PERIOD_SET.TENANT_ID.eq(tenant.value))
                    .and(ENTITY_PERIOD_SET.ENTITY_REF.eq(entityRef)),
            )
        if (!exists) return null
        return dsl
            .selectFrom(ENTITY_PERIOD)
            .where(ENTITY_PERIOD.TENANT_ID.eq(tenant.value))
            .and(ENTITY_PERIOD.ENTITY_REF.eq(entityRef))
            .orderBy(ENTITY_PERIOD.PERIOD_END, ENTITY_PERIOD.PERIOD_REF)
            .fetch()
            .map {
                PeriodSpan(
                    periodRef = checkNotNull(it.get(ENTITY_PERIOD.PERIOD_REF)),
                    periodStart = checkNotNull(it.get(ENTITY_PERIOD.PERIOD_START)),
                    periodEnd = checkNotNull(it.get(ENTITY_PERIOD.PERIOD_END)),
                )
            }
    }
}
