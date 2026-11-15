package com.dynokore.gst.canonical.adapters.persistence

import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.PERIOD_SPAN
import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.PERIOD_SPAN_VERSION
import com.dynokore.gst.canonical.application.document.PeriodSpans
import com.dynokore.gst.canonical.domain.dating.PeriodSpan
import org.jooq.DSLContext
import java.util.UUID

/** The complete set of period spans in force for one entity, at the event version that carried it. */
data class PeriodSpanSet(
    val tenantId: UUID,
    val entityRef: String,
    val version: Long,
    val spans: List<PeriodSpan>,
    /** Period reference to the id of the period record in force (INV-AC-21535); a period without a key has none. */
    val periodIds: Map<String, UUID> = emptyMap(),
) {
    init {
        val refs = spans.map { it.periodRef }.toSet()
        require(refs.containsAll(periodIds.keys)) { "periodIds names a period that is no span of the set" }
    }
}

/**
 * TPL-11. jOOQ only. The DSLContext is the one of the open `canonical` transaction; forced row-level security binds
 * every statement to the tenant of the session. The version row is the guard: `apply` upserts it only for a higher
 * version, so concurrent applies serialize on that row and cannot lose a newer set; only when it changed are the
 * spans of the entity replaced, in the caller's transaction (event-carried state transfer, INV-Q-21406 A).
 */
class JooqPeriodSpans(
    private val dsl: DSLContext,
) : PeriodSpans {
    /** True when the set was newer than the held one and replaced it; false when it was ignored. */
    fun apply(set: PeriodSpanSet): Boolean {
        val changed =
            dsl
                .insertInto(PERIOD_SPAN_VERSION)
                .set(PERIOD_SPAN_VERSION.TENANT_ID, set.tenantId)
                .set(PERIOD_SPAN_VERSION.ENTITY_REF, set.entityRef)
                .set(PERIOD_SPAN_VERSION.VERSION, set.version)
                .onConflict(PERIOD_SPAN_VERSION.TENANT_ID, PERIOD_SPAN_VERSION.ENTITY_REF)
                .doUpdate()
                .set(PERIOD_SPAN_VERSION.VERSION, set.version)
                .where(PERIOD_SPAN_VERSION.VERSION.lt(set.version))
                .execute()
        if (changed == 0) return false
        dsl
            .deleteFrom(PERIOD_SPAN)
            .where(PERIOD_SPAN.TENANT_ID.eq(set.tenantId))
            .and(PERIOD_SPAN.ENTITY_REF.eq(set.entityRef))
            .execute()
        set.spans.forEach { span ->
            dsl
                .insertInto(PERIOD_SPAN)
                .set(PERIOD_SPAN.TENANT_ID, set.tenantId)
                .set(PERIOD_SPAN.ENTITY_REF, set.entityRef)
                .set(PERIOD_SPAN.PERIOD_REF, span.periodRef)
                .set(PERIOD_SPAN.PERIOD_START, span.start)
                .set(PERIOD_SPAN.PERIOD_END, span.end)
                .set(PERIOD_SPAN.PERIOD_ID, set.periodIds[span.periodRef])
                .execute()
        }
        return true
    }

    /** The id of the period record of one period, or null when the period is unknown or was stored without an id. */
    fun periodIdOf(
        tenantId: UUID,
        entityRef: String,
        periodRef: String,
    ): UUID? =
        dsl
            .select(PERIOD_SPAN.PERIOD_ID)
            .from(PERIOD_SPAN)
            .where(PERIOD_SPAN.TENANT_ID.eq(tenantId))
            .and(PERIOD_SPAN.ENTITY_REF.eq(entityRef))
            .and(PERIOD_SPAN.PERIOD_REF.eq(periodRef))
            .fetchOne(PERIOD_SPAN.PERIOD_ID)

    override fun spansOf(
        tenantId: UUID,
        entityRef: String,
    ): List<PeriodSpan> =
        dsl
            .select(PERIOD_SPAN.PERIOD_REF, PERIOD_SPAN.PERIOD_START, PERIOD_SPAN.PERIOD_END)
            .from(PERIOD_SPAN)
            .where(PERIOD_SPAN.TENANT_ID.eq(tenantId))
            .and(PERIOD_SPAN.ENTITY_REF.eq(entityRef))
            .orderBy(PERIOD_SPAN.PERIOD_START, PERIOD_SPAN.PERIOD_REF)
            .fetch {
                PeriodSpan(
                    checkNotNull(it.get(PERIOD_SPAN.PERIOD_REF)),
                    checkNotNull(it.get(PERIOD_SPAN.PERIOD_START)),
                    checkNotNull(it.get(PERIOD_SPAN.PERIOD_END)),
                )
            }
}
