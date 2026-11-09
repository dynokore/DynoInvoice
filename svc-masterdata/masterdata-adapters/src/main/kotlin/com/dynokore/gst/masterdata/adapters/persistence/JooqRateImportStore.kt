package com.dynokore.gst.masterdata.adapters.persistence

import com.dynokore.gst.masterdata.adapters.http.RateWireCodes
import com.dynokore.gst.masterdata.adapters.persistence.jooq.tables.references.RATE_IMPORT
import com.dynokore.gst.masterdata.application.gapreport.RecordState
import com.dynokore.gst.masterdata.application.rate.RateImportRecord
import com.dynokore.gst.masterdata.application.rate.RateImportStore
import com.dynokore.gst.masterdata.application.rate.RateRowRejection
import com.dynokore.gst.masterdata.application.rate.RejectedRateRow
import com.dynokore.platform.outboxandqueue.UuidV7
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.Problems
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * TPL-11. The rate import records (API-17, INV-REQ-833): a RUNNING record is committed before the load starts. The
 * rejected rows are stored as `{row, code, field, message}`; `code` comes from [RateWireCodes.rowCode] of the pinned
 * catalogue and the message is the catalogue condition of that code.
 */
class JooqRateImportStore(
    private val dsl: DSLContext,
    private val clock: Clock,
    private val mapper: ObjectMapper,
    private val ids: UuidV7 = UuidV7(),
) : RateImportStore {
    override fun insertRunning(
        importId: String,
        entity: String,
        actor: String,
        source: String,
        rangeFrom: LocalDate,
        rangeTo: LocalDate,
    ): RateImportRecord {
        val started = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val at = started.atOffset(ZoneOffset.UTC)
        dsl
            .insertInto(RATE_IMPORT)
            .set(RATE_IMPORT.ID, ids.next(started))
            .set(RATE_IMPORT.IMPORT_ID, importId)
            .set(RATE_IMPORT.ENTITY_REF, entity)
            .set(RATE_IMPORT.ACTOR_REF, actor)
            .set(RATE_IMPORT.SOURCE, source)
            .set(RATE_IMPORT.RANGE_FROM, rangeFrom)
            .set(RATE_IMPORT.RANGE_TO, rangeTo)
            .set(RATE_IMPORT.STATE, RecordState.RUNNING.name)
            .set(RATE_IMPORT.STARTED_AT, at)
            .set(RATE_IMPORT.CREATED_AT, at)
            .set(RATE_IMPORT.UPDATED_AT, at)
            .set(RATE_IMPORT.VERSION, 0L)
            .execute()
        return RateImportRecord(
            importId,
            entity,
            actor,
            source,
            rangeFrom,
            rangeTo,
            RecordState.RUNNING,
            started,
            null,
            null,
            null,
            null,
            null,
        )
    }

    override fun finish(
        importId: String,
        completedAt: Instant,
        appliedCount: Int,
        failedCount: Int,
        rejectedRows: List<RejectedRateRow>,
    ) {
        val rejected = mapper.createArrayNode()
        rejectedRows.forEach { r ->
            val code = RateWireCodes.rowCode(r.reason)
            rejected.add(
                mapper.createObjectNode().also {
                    it.put("row", r.row)
                    it.put("code", code.code)
                    it.put("field", r.field)
                    it.put("message", code.condition)
                },
            )
        }
        val at = completedAt.atOffset(ZoneOffset.UTC)
        dsl
            .update(RATE_IMPORT)
            .set(RATE_IMPORT.STATE, RecordState.SUCCEEDED.name)
            .set(RATE_IMPORT.COMPLETED_AT, at)
            .set(RATE_IMPORT.APPLIED_COUNT, appliedCount)
            .set(RATE_IMPORT.FAILED_COUNT, failedCount)
            .set(RATE_IMPORT.REJECTED_ROWS, jsonbOf(rejected.toString()))
            .set(RATE_IMPORT.UPDATED_AT, at)
            .set(RATE_IMPORT.VERSION, RATE_IMPORT.VERSION.plus(1L))
            .where(RATE_IMPORT.IMPORT_ID.eq(importId), RATE_IMPORT.STATE.eq(RecordState.RUNNING.name))
            .execute()
    }

    override fun fail(importId: String) {
        failWhere(RATE_IMPORT.IMPORT_ID.eq(importId))
    }

    override fun failRunningBefore(before: Instant): Int =
        failWhere(RATE_IMPORT.STARTED_AT.lt(before.atOffset(ZoneOffset.UTC)))

    private fun failWhere(condition: org.jooq.Condition): Int {
        val at = clock.instant().atOffset(ZoneOffset.UTC)
        val envelope = Problems.render(ClosedErrorCode.INTERNAL_ERROR, UUID.randomUUID().toString()).body
        return dsl
            .update(RATE_IMPORT)
            .set(RATE_IMPORT.STATE, RecordState.FAILED.name)
            .set(RATE_IMPORT.COMPLETED_AT, at)
            .set(RATE_IMPORT.ERROR, jsonbOf(envelope))
            .set(RATE_IMPORT.UPDATED_AT, at)
            .set(RATE_IMPORT.VERSION, RATE_IMPORT.VERSION.plus(1L))
            .where(condition, RATE_IMPORT.STATE.eq(RecordState.RUNNING.name))
            .execute()
    }

    override fun find(importId: String): RateImportRecord? =
        dsl
            .selectFrom(RATE_IMPORT)
            .where(RATE_IMPORT.IMPORT_ID.eq(importId))
            .fetchOne()
            ?.let { r ->
                RateImportRecord(
                    importId = r.importId.column("import_id"),
                    entity = r.entityRef.column("entity_ref"),
                    actor = r.actorRef.column("actor_ref"),
                    source = r.source.column("source"),
                    rangeFrom = r.rangeFrom.column("range_from"),
                    rangeTo = r.rangeTo.column("range_to"),
                    state = RecordState.valueOf(r.state.column("state")),
                    startedAt = r.startedAt.column("started_at").toInstant(),
                    completedAt = r.completedAt?.toInstant(),
                    appliedCount = r.appliedCount,
                    failedCount = r.failedCount,
                    rejectedRows = r.rejectedRows?.let(::rejectedRows),
                    error = r.error?.data(),
                )
            }

    private fun rejectedRows(json: JSONB): List<RejectedRateRow> =
        mapper.readTree(json.data()).values().map { node ->
            val code = node.get("code").asString()
            RejectedRateRow(
                node.get("row").asInt(),
                RateRowRejection.entries.first { RateWireCodes.rowCode(it).code == code },
                node.get("field").asString(),
            )
        }

    private fun jsonbOf(text: String) = DSL.value(text).cast(SQLDataType.JSONB)
}
