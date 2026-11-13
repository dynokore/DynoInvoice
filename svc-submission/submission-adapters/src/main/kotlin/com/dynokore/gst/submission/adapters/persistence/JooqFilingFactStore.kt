package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.PeriodDueDate
import com.dynokore.gst.submission.application.filing.FilingFact
import com.dynokore.gst.submission.application.filing.FilingFactStore
import com.dynokore.gst.submission.application.filing.NewFilingFact
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import java.time.Clock
import java.time.ZoneOffset
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.FilingFact as FilingFactTable

/**
 * [FilingFactStore] over `submission.filing_fact` (INV-REQ-917, INV-T-20626). Every statement runs in the caller's
 * open tenant transaction and filters by the bound tenant as well as by row level security. [append] first locks the
 * period's due date row (`select ... for update`), so appends of one (entity, period) are serialised, then inserts
 * `on conflict do nothing` on the unique key: an identical fact is stored once and keeps the first row's
 * `recorded_at`. `recorded_at` is the [clock] instant, never SQL `now()`.
 */
class JooqFilingFactStore(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
    private val clock: Clock,
) : FilingFactStore {
    private val f = FilingFactTable.FILING_FACT
    private val p = PeriodDueDate.PERIOD_DUE_DATE

    override fun facts(
        entityRef: String,
        periodRef: String,
    ): List<FilingFact> =
        dsl
            .selectFrom(f)
            .where(f.TENANT_ID.eq(tenants.current().value))
            .and(f.ENTITY_REF.eq(entityRef))
            .and(f.PERIOD_REF.eq(periodRef))
            .orderBy(f.SEQ.asc())
            .fetch()
            .map {
                FilingFact(
                    entityRef = checkNotNull(it.entityRef),
                    periodRef = checkNotNull(it.periodRef),
                    filedAt = checkNotNull(it.filedAt),
                    acknowledgementRef = checkNotNull(it.acknowledgementRef),
                    portalRef = it.portalRef,
                    correction = checkNotNull(it.isCorrection),
                    recordedBy = checkNotNull(it.recordedBy),
                    recordedAt = checkNotNull(it.recordedAt).toInstant(),
                )
            }

    override fun append(fact: NewFilingFact) {
        val tenant = tenants.current().value
        dsl
            .selectOne()
            .from(p)
            .where(p.TENANT_ID.eq(tenant))
            .and(p.ENTITY_REF.eq(fact.entityRef))
            .and(p.PERIOD_REF.eq(fact.periodRef))
            .forUpdate()
            .fetch()
        dsl
            .insertInto(f)
            .set(f.TENANT_ID, tenant)
            .set(f.ENTITY_REF, fact.entityRef)
            .set(f.PERIOD_REF, fact.periodRef)
            .set(f.FILED_AT, fact.filedAt)
            .set(f.ACKNOWLEDGEMENT_REF, fact.acknowledgementRef)
            .set(f.PORTAL_REF, fact.portalRef)
            .set(f.IS_CORRECTION, fact.correction)
            .set(f.RECORDED_BY, fact.recordedBy)
            .set(f.RECORDED_AT, clock.instant().atOffset(ZoneOffset.UTC))
            .onConflict(f.TENANT_ID, f.ENTITY_REF, f.PERIOD_REF, f.FILED_AT, f.ACKNOWLEDGEMENT_REF, f.IS_CORRECTION)
            .doNothing()
            .execute()
    }
}
