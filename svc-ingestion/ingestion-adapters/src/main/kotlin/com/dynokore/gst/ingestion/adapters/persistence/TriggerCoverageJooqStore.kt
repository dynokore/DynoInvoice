package com.dynokore.gst.ingestion.adapters.persistence

import com.dynokore.gst.ingestion.adapters.persistence.jooq.tables.TriggerCoverage.Companion.TRIGGER_COVERAGE
import com.dynokore.gst.ingestion.application.triggers.TriggerCoverageStore
import com.dynokore.gst.ingestion.domain.triggers.CoverageRecord
import com.dynokore.gst.ingestion.domain.triggers.TriggerClass
import org.jooq.DSLContext
import org.jooq.Record
import java.time.Clock
import java.util.UUID

/**
 * jOOQ store of `ingestion.trigger_coverage` (INV-REQ-788): one row per (tenant, entity, class), saved as an upsert on
 * that key in the caller's transaction (the transaction runner binds the tenant; the table's row-level security checks
 * it). The row keeps the acting user id and the time, never a role. It is a plain class: the composition root binds it.
 */
class TriggerCoverageJooqStore(
    private val dsl: DSLContext,
    private val clock: Clock,
) : TriggerCoverageStore {
    override fun find(
        tenantId: UUID,
        entityRef: String,
        triggerClass: TriggerClass,
    ): CoverageRecord? =
        dsl
            .selectFrom(TRIGGER_COVERAGE)
            .where(TRIGGER_COVERAGE.TENANT_ID.eq(tenantId))
            .and(TRIGGER_COVERAGE.ENTITY_REF.eq(entityRef))
            .and(TRIGGER_COVERAGE.TRIGGER_CLASS.eq(triggerClass.name))
            .fetchOne()
            ?.let(::record)

    override fun save(
        tenantId: UUID,
        entityRef: String,
        triggerClass: TriggerClass,
        record: CoverageRecord,
        actorUserId: UUID,
    ) {
        val at = clock.instant().atOffset(java.time.ZoneOffset.UTC)
        val row =
            dsl
                .newRecord(TRIGGER_COVERAGE)
                .also {
                    it.tenantId = tenantId
                    it.entityRef = entityRef
                    it.triggerClass = triggerClass.name
                    it.recordedBy = actorUserId
                    it.recordedAt = at
                    when (record) {
                        is CoverageRecord.Mapped -> {
                            it.status = MAPPED
                            it.adapterRef = record.adapterRef
                        }
                        is CoverageRecord.Waived -> {
                            it.status = WAIVED
                            it.reviewDate = record.reviewDate
                        }
                        is CoverageRecord.CoveredElsewhere -> {
                            it.status = COVERED
                            it.coveringSolution = record.coveringSolution
                            it.evidenceReference = record.evidenceReference
                        }
                    }
                }
        dsl
            .insertInto(TRIGGER_COVERAGE)
            .set(row)
            .onConflict(TRIGGER_COVERAGE.TENANT_ID, TRIGGER_COVERAGE.ENTITY_REF, TRIGGER_COVERAGE.TRIGGER_CLASS)
            .doUpdate()
            .set(TRIGGER_COVERAGE.STATUS, row.status)
            .set(TRIGGER_COVERAGE.ADAPTER_REF, row.adapterRef)
            .set(TRIGGER_COVERAGE.REVIEW_DATE, row.reviewDate)
            .set(TRIGGER_COVERAGE.COVERING_SOLUTION, row.coveringSolution)
            .set(TRIGGER_COVERAGE.EVIDENCE_REFERENCE, row.evidenceReference)
            .set(TRIGGER_COVERAGE.RECORDED_BY, row.recordedBy)
            .set(TRIGGER_COVERAGE.RECORDED_AT, row.recordedAt)
            .execute()
    }

    private fun record(row: Record): CoverageRecord {
        val r = row.into(TRIGGER_COVERAGE)
        return when (r.status) {
            MAPPED -> CoverageRecord.Mapped(checkNotNull(r.adapterRef))
            WAIVED -> CoverageRecord.Waived(checkNotNull(r.reviewDate))
            COVERED ->
                CoverageRecord.CoveredElsewhere(checkNotNull(r.coveringSolution), checkNotNull(r.evidenceReference))
            else -> error("unknown coverage status ${r.status}")
        }
    }

    private companion object {
        const val MAPPED = "MAPPED"
        const val WAIVED = "WAIVED"
        const val COVERED = "COVERED_BY_OTHER_SOLUTION"
    }
}
