package com.dynokore.gst.masterdata.adapters.persistence

import com.dynokore.gst.masterdata.adapters.persistence.jooq.tables.references.GAP_REPORT
import com.dynokore.gst.masterdata.application.gapreport.GapFinding
import com.dynokore.gst.masterdata.application.gapreport.GapReportRecord
import com.dynokore.gst.masterdata.application.gapreport.GapReportResult
import com.dynokore.gst.masterdata.application.gapreport.GapReportStore
import com.dynokore.gst.masterdata.application.gapreport.RecordState
import com.dynokore.gst.masterdata.domain.counterparty.GapClass
import com.dynokore.platform.outboxandqueue.UuidV7
import org.jooq.DSLContext
import org.jooq.JSONB
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * TPL-11. The gap report records (API-17): a RUNNING record is inserted by the caller's own committed transaction and
 * finalised once, SUCCEEDED with its result or FAILED with the API-20 envelope. The tenant column is added by the
 * tenancy-jooq listeners; forced row-level security stays behind them.
 */
class JooqGapReportStore(
    private val dsl: DSLContext,
    private val clock: Clock,
    private val mapper: ObjectMapper,
    private val ids: UuidV7 = UuidV7(),
) : GapReportStore {
    override fun insertRunning(record: GapReportRecord) {
        val now = clock.instant().atOffset(ZoneOffset.UTC)
        dsl
            .insertInto(GAP_REPORT)
            .set(GAP_REPORT.ID, ids.next(clock.instant()))
            .set(GAP_REPORT.GAP_REPORT_ID, record.gapReportId)
            .set(GAP_REPORT.ENTITY_REF, record.entity)
            .set(GAP_REPORT.ACTOR_REF, record.actor)
            .set(GAP_REPORT.STATE, RecordState.RUNNING.name)
            .set(GAP_REPORT.STARTED_AT, record.startedAt.atOffset(ZoneOffset.UTC))
            .set(GAP_REPORT.CREATED_AT, now)
            .set(GAP_REPORT.UPDATED_AT, now)
            .set(GAP_REPORT.VERSION, 0L)
            .execute()
    }

    override fun finish(
        gapReportId: String,
        completedAt: Instant,
        result: GapReportResult,
    ) {
        val root = mapper.createObjectNode()
        val rows = root.putArray("rows")
        result.rows.forEach {
            val row = rows.addObject()
            row.put("counterpartyRef", it.counterpartyRef)
            row.put("findingClass", it.findingClass.name)
            it.errorCode?.let { code -> row.put("errorCode", code) }
        }
        val notChecked = root.putArray("notChecked")
        result.notChecked.forEach { notChecked.add(it) }
        close(gapReportId, completedAt, RecordState.SUCCEEDED, GAP_REPORT.RESULT, root.toString())
    }

    override fun fail(
        gapReportId: String,
        completedAt: Instant,
        error: String,
    ) = close(gapReportId, completedAt, RecordState.FAILED, GAP_REPORT.ERROR, error)

    private fun close(
        gapReportId: String,
        completedAt: Instant,
        state: RecordState,
        column: org.jooq.TableField<*, JSONB?>,
        json: String,
    ) {
        val at = completedAt.atOffset(ZoneOffset.UTC)
        @Suppress("UNCHECKED_CAST")
        dsl
            .update(GAP_REPORT)
            .set(GAP_REPORT.STATE, state.name)
            .set(GAP_REPORT.COMPLETED_AT, at)
            .set(column as org.jooq.Field<JSONB?>, JSONB.jsonb(json))
            .set(GAP_REPORT.UPDATED_AT, at)
            .set(GAP_REPORT.VERSION, GAP_REPORT.VERSION.plus(1L))
            .where(GAP_REPORT.GAP_REPORT_ID.eq(gapReportId), GAP_REPORT.STATE.eq(RecordState.RUNNING.name))
            .execute()
    }

    override fun find(gapReportId: String): GapReportRecord? =
        dsl
            .selectFrom(GAP_REPORT)
            .where(GAP_REPORT.GAP_REPORT_ID.eq(gapReportId))
            .fetchOne()
            ?.let { r ->
                GapReportRecord(
                    gapReportId = r.gapReportId.column("gap_report_id"),
                    entity = r.entityRef.column("entity_ref"),
                    actor = r.actorRef.column("actor_ref"),
                    state = RecordState.valueOf(r.state.column("state")),
                    startedAt = r.startedAt.column("started_at").toInstant(),
                    completedAt = r.completedAt?.toInstant(),
                    result = r.result?.let { parse(it.data()) },
                    error = r.error?.data(),
                )
            }

    private fun parse(json: String): GapReportResult {
        val node = mapper.readTree(json)
        val rows =
            node.get("rows").values().map {
                GapFinding(
                    it.get("counterpartyRef").asString(),
                    GapClass.valueOf(it.get("findingClass").asString()),
                    it.get("errorCode")?.asString(),
                )
            }
        return GapReportResult(rows, node.get("notChecked").values().map { it.asString() })
    }
}
