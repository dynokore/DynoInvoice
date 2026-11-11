package com.dynokore.gst.aggregation.adapters.periods

import com.dynokore.gst.aggregation.adapters.persistence.jooq.tables.PeriodSpan.Companion.PERIOD_SPAN
import com.dynokore.gst.aggregation.adapters.persistence.jooq.tables.PeriodSpanVersion.Companion.PERIOD_SPAN_VERSION
import com.dynokore.gst.aggregation.domain.PeriodSpan
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.LocalDate

/**
 * The period spans of an entity as `aggregation.period_span` and the version last applied as
 * `aggregation.period_span_version` (INV-REQ-435, INV-Q-16535 A). Every call runs in a tenant transaction and filters
 * on the bound tenant, so the tenant is part of every key and row-level security backs it.
 */
class JooqPeriodSpanStore(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
    private val tenants: TenantContext,
) {
    /**
     * Replaces the whole set of [entityRef] with [periods] in one transaction, only when [version] is greater than the
     * stored one (or none is stored). Returns whether it applied.
     */
    fun replace(
        entityRef: String,
        version: Long,
        periods: List<PeriodSpan>,
    ): Boolean =
        runner.inTransaction {
            val tenant = tenants.current().value
            val advanced =
                dsl
                    .insertInto(PERIOD_SPAN_VERSION)
                    .set(PERIOD_SPAN_VERSION.TENANT_ID, tenant)
                    .set(PERIOD_SPAN_VERSION.ENTITY_REF, entityRef)
                    .set(PERIOD_SPAN_VERSION.VERSION, version)
                    .onConflict(PERIOD_SPAN_VERSION.TENANT_ID, PERIOD_SPAN_VERSION.ENTITY_REF)
                    .doUpdate()
                    .set(PERIOD_SPAN_VERSION.VERSION, version)
                    .set(PERIOD_SPAN_VERSION.UPDATED_AT, DSL.currentOffsetDateTime())
                    .where(PERIOD_SPAN_VERSION.VERSION.lt(version))
                    .execute()
            if (advanced == 0) return@inTransaction false
            dsl
                .deleteFrom(PERIOD_SPAN)
                .where(PERIOD_SPAN.TENANT_ID.eq(tenant))
                .and(PERIOD_SPAN.ENTITY_REF.eq(entityRef))
                .execute()
            periods.forEach { span ->
                dsl
                    .insertInto(PERIOD_SPAN)
                    .set(PERIOD_SPAN.TENANT_ID, tenant)
                    .set(PERIOD_SPAN.ENTITY_REF, entityRef)
                    .set(PERIOD_SPAN.PERIOD_REF, span.periodRef)
                    .set(PERIOD_SPAN.PERIOD_START, span.start)
                    .set(PERIOD_SPAN.PERIOD_END, span.end)
                    .execute()
            }
            true
        }

    /** The stored spans of [entityRef] that overlap [from] to [to], earliest first; null when no set is stored. */
    fun overlapping(
        entityRef: String,
        from: LocalDate,
        to: LocalDate,
    ): List<PeriodSpan>? =
        runner.inTransaction {
            val tenant = tenants.current().value
            val known =
                dsl.fetchExists(
                    PERIOD_SPAN_VERSION,
                    PERIOD_SPAN_VERSION.TENANT_ID.eq(tenant).and(PERIOD_SPAN_VERSION.ENTITY_REF.eq(entityRef)),
                )
            if (!known) return@inTransaction null
            dsl
                .selectFrom(PERIOD_SPAN)
                .where(PERIOD_SPAN.TENANT_ID.eq(tenant))
                .and(PERIOD_SPAN.ENTITY_REF.eq(entityRef))
                .and(PERIOD_SPAN.PERIOD_START.le(to))
                .and(PERIOD_SPAN.PERIOD_END.ge(from))
                .orderBy(PERIOD_SPAN.PERIOD_START, PERIOD_SPAN.PERIOD_REF)
                .fetch()
                .map {
                    PeriodSpan(checkNotNull(it.periodStart), checkNotNull(it.periodEnd), checkNotNull(it.periodRef))
                }
        }
}
