package com.dynokore.gst.masterdata.adapters.persistence

import com.dynokore.gst.masterdata.adapters.persistence.jooq.tables.references.COUNTERPARTY_IMPORT
import com.dynokore.gst.masterdata.adapters.persistence.jooq.tables.references.COUNTERPARTY_IMPORT_DRY_RUN
import com.dynokore.gst.masterdata.application.gapreport.RecordState
import com.dynokore.gst.masterdata.application.imports.DryRunRecord
import com.dynokore.gst.masterdata.application.imports.DryRunStore
import com.dynokore.gst.masterdata.application.imports.ImportProvenance
import com.dynokore.gst.masterdata.application.imports.ImportRecordData
import com.dynokore.gst.masterdata.application.imports.ImportStore
import com.dynokore.gst.masterdata.domain.counterparty.ImportAnalysis
import com.dynokore.gst.masterdata.domain.counterparty.ImportChange
import com.dynokore.gst.masterdata.domain.counterparty.ImportDuplicate
import com.dynokore.gst.masterdata.domain.counterparty.ImportRow
import com.dynokore.gst.masterdata.domain.counterparty.ImportTarget
import com.dynokore.gst.masterdata.domain.counterparty.PartyRef
import com.dynokore.gst.masterdata.domain.counterparty.RejectedImportRow
import com.dynokore.platform.outboxandqueue.UuidV7
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.Problems
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

private fun failureEnvelope() = Problems.render(ClosedErrorCode.INTERNAL_ERROR, UUID.randomUUID().toString()).body

private fun jsonb(text: String) = DSL.value(text).cast(SQLDataType.JSONB)

private fun OffsetDateTime.instant(): Instant = toInstant()

/** TPL-11. The stored import dry runs (API-17): rows and result are jsonb; the file hash is stored as given. */
class JooqDryRunStore(
    private val dsl: DSLContext,
    private val clock: Clock,
    private val mapper: ObjectMapper,
    private val ids: UuidV7 = UuidV7(),
) : DryRunStore {
    override fun insertRunning(
        dryRunId: String,
        actor: String,
        fileHash: String,
        rows: List<ImportRow>,
    ): DryRunRecord {
        val started = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val at = started.atOffset(ZoneOffset.UTC)
        dsl
            .insertInto(COUNTERPARTY_IMPORT_DRY_RUN)
            .set(COUNTERPARTY_IMPORT_DRY_RUN.ID, ids.next(started))
            .set(COUNTERPARTY_IMPORT_DRY_RUN.DRY_RUN_ID, dryRunId)
            .set(COUNTERPARTY_IMPORT_DRY_RUN.ACTOR_REF, actor)
            .set(COUNTERPARTY_IMPORT_DRY_RUN.FILE_HASH, fileHash)
            .set(COUNTERPARTY_IMPORT_DRY_RUN.ROW_COUNT, rows.size)
            .set(COUNTERPARTY_IMPORT_DRY_RUN.ROWS, jsonb(rowsJson(rows)))
            .set(COUNTERPARTY_IMPORT_DRY_RUN.STATE, RecordState.RUNNING.name)
            .set(COUNTERPARTY_IMPORT_DRY_RUN.STARTED_AT, at)
            .set(COUNTERPARTY_IMPORT_DRY_RUN.CREATED_AT, at)
            .set(COUNTERPARTY_IMPORT_DRY_RUN.UPDATED_AT, at)
            .set(COUNTERPARTY_IMPORT_DRY_RUN.VERSION, 0L)
            .execute()
        return DryRunRecord(dryRunId, actor, fileHash, rows.size, rows, RecordState.RUNNING, started, null, null, null)
    }

    override fun finish(
        dryRunId: String,
        analysis: ImportAnalysis,
    ) {
        val at = clock.instant().atOffset(ZoneOffset.UTC)
        dsl
            .update(COUNTERPARTY_IMPORT_DRY_RUN)
            .set(COUNTERPARTY_IMPORT_DRY_RUN.STATE, RecordState.SUCCEEDED.name)
            .set(COUNTERPARTY_IMPORT_DRY_RUN.COMPLETED_AT, at)
            .set(COUNTERPARTY_IMPORT_DRY_RUN.RESULT, jsonb(resultJson(analysis)))
            .set(COUNTERPARTY_IMPORT_DRY_RUN.UPDATED_AT, at)
            .set(COUNTERPARTY_IMPORT_DRY_RUN.VERSION, COUNTERPARTY_IMPORT_DRY_RUN.VERSION.plus(1L))
            .where(
                COUNTERPARTY_IMPORT_DRY_RUN.DRY_RUN_ID.eq(dryRunId),
                COUNTERPARTY_IMPORT_DRY_RUN.STATE.eq(RecordState.RUNNING.name),
            ).execute()
    }

    override fun fail(dryRunId: String) {
        failWhere(COUNTERPARTY_IMPORT_DRY_RUN.DRY_RUN_ID.eq(dryRunId))
    }

    override fun failRunningBefore(before: Instant): Int =
        failWhere(COUNTERPARTY_IMPORT_DRY_RUN.STARTED_AT.lt(before.atOffset(ZoneOffset.UTC)))

    private fun failWhere(condition: org.jooq.Condition): Int {
        val at = clock.instant().atOffset(ZoneOffset.UTC)
        return dsl
            .update(COUNTERPARTY_IMPORT_DRY_RUN)
            .set(COUNTERPARTY_IMPORT_DRY_RUN.STATE, RecordState.FAILED.name)
            .set(COUNTERPARTY_IMPORT_DRY_RUN.COMPLETED_AT, at)
            .set(COUNTERPARTY_IMPORT_DRY_RUN.ERROR, jsonb(failureEnvelope()))
            .set(COUNTERPARTY_IMPORT_DRY_RUN.UPDATED_AT, at)
            .set(COUNTERPARTY_IMPORT_DRY_RUN.VERSION, COUNTERPARTY_IMPORT_DRY_RUN.VERSION.plus(1L))
            .where(condition, COUNTERPARTY_IMPORT_DRY_RUN.STATE.eq(RecordState.RUNNING.name))
            .execute()
    }

    override fun find(dryRunId: String): DryRunRecord? =
        dsl
            .selectFrom(COUNTERPARTY_IMPORT_DRY_RUN)
            .where(COUNTERPARTY_IMPORT_DRY_RUN.DRY_RUN_ID.eq(dryRunId))
            .fetchOne()
            ?.let { r ->
                DryRunRecord(
                    dryRunId = r.dryRunId.column("dry_run_id"),
                    actor = r.actorRef.column("actor_ref"),
                    fileHash = r.fileHash.column("file_hash"),
                    rowCount = r.rowCount.column("row_count"),
                    rows = rowsOf(mapper.readTree(r.rows.column("rows").data())),
                    state = RecordState.valueOf(r.state.column("state")),
                    startedAt = r.startedAt.column("started_at").instant(),
                    completedAt = r.completedAt?.instant(),
                    result = r.result?.let { analysisOf(mapper.readTree(it.data())) },
                    error = r.error?.data(),
                )
            }

    private fun rowsJson(rows: List<ImportRow>): String {
        val array = mapper.createArrayNode()
        rows.forEach {
            val node = array.addObject()
            it.counterpartyRef?.let { v -> node.put("counterpartyRef", v) }
            it.uen?.let { v -> node.put("uen", v) }
            it.legalName?.let { v -> node.put("legalName", v) }
            it.addressLine1?.let { v -> node.put("addressLine1", v) }
            it.postalCode?.let { v -> node.put("postalCode", v) }
        }
        return array.toString()
    }

    private fun rowsOf(node: JsonNode): List<ImportRow> =
        node.values().map {
            ImportRow(
                text(it, "counterpartyRef"),
                text(it, "uen"),
                text(it, "legalName"),
                text(it, "addressLine1"),
                text(it, "postalCode"),
            )
        }

    private fun resultJson(a: ImportAnalysis): String {
        val root = mapper.createObjectNode()
        val rejected = root.putArray("rejected")
        a.rejected.forEach { rejected.add(rejectedNode(mapper, it)) }
        val duplicates = root.putArray("duplicates")
        a.duplicates.forEach {
            val n = duplicates.addObject()
            n.put("uen", it.uen)
            val rows = n.putArray("rows")
            it.rows.forEach { row -> rows.add(row) }
            it.existingCounterpartyRef?.let { ref -> n.put("existingCounterpartyRef", ref) }
        }
        val targets = root.putArray("targets")
        a.targets.forEach {
            val n = targets.addObject()
            n.put("row", it.row)
            n.put("party", it.party.value)
        }
        root.put("createCount", a.createCount)
        root.put("updateCount", a.updateCount)
        root.put("unchangedCount", a.unchangedCount)
        val changes = root.putArray("changes")
        a.changes.forEach {
            val n = changes.addObject()
            n.put("uen", it.uen)
            n.put("field", it.field)
            n.put("newValue", it.newValue)
            n.put("counterpartyRef", it.counterpartyRef)
            it.oldValue?.let { v -> n.put("oldValue", v) }
        }
        return root.toString()
    }

    private fun analysisOf(node: JsonNode): ImportAnalysis =
        ImportAnalysis(
            rejected = node.get("rejected").values().map(::rejectedOf),
            duplicates =
                node.get("duplicates").values().map { d ->
                    ImportDuplicate(
                        d.get("uen").asString(),
                        d.get("rows").values().map { it.asInt() },
                        text(d, "existingCounterpartyRef"),
                    )
                },
            targets =
                node.get("targets").values().map {
                    ImportTarget(
                        it.get("row").asInt(),
                        PartyRef(it.get("party").asString()),
                        ImportRow(null, null, null, null, null),
                    )
                },
            createCount = node.get("createCount").asInt(),
            updateCount = node.get("updateCount").asInt(),
            unchangedCount = node.get("unchangedCount").asInt(),
            changes =
                node.get("changes").values().map {
                    ImportChange(
                        it.get("uen").asString(),
                        it.get("field").asString(),
                        it.get("newValue").asString(),
                        it.get("counterpartyRef").asString(),
                        text(it, "oldValue"),
                    )
                },
        )
}

internal fun text(
    node: JsonNode,
    name: String,
): String? = node.get(name)?.takeIf { !it.isNull }?.asString()

internal fun rejectedNode(
    mapper: ObjectMapper,
    r: RejectedImportRow,
) = mapper.createObjectNode().also {
    it.put("row", r.row)
    it.put("code", r.code)
    it.put("message", r.message)
    r.field?.let { f -> it.put("field", f) }
}

internal fun rejectedOf(node: JsonNode) =
    RejectedImportRow(
        node.get("row").asInt(),
        node.get("code").asString(),
        node.get("message").asString(),
        text(node, "field"),
    )

/** TPL-11. The FR-AG.57 import records (API-17): a RUNNING record is committed before the apply starts. */
class JooqImportStore(
    private val dsl: DSLContext,
    private val clock: Clock,
    private val mapper: ObjectMapper,
    private val ids: UuidV7 = UuidV7(),
) : ImportStore {
    override fun insertRunning(
        importId: String,
        entity: String,
        actor: String,
        dryRunId: String,
        fileHash: String,
        providedBy: ImportProvenance,
    ): ImportRecordData? {
        val started = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val at = started.atOffset(ZoneOffset.UTC)
        val inserted =
            dsl
                .insertInto(COUNTERPARTY_IMPORT)
                .set(COUNTERPARTY_IMPORT.ID, ids.next(started))
                .set(COUNTERPARTY_IMPORT.IMPORT_ID, importId)
                .set(COUNTERPARTY_IMPORT.ENTITY_REF, entity)
                .set(COUNTERPARTY_IMPORT.ACTOR_REF, actor)
                .set(COUNTERPARTY_IMPORT.DRY_RUN_ID, dryRunId)
                .set(COUNTERPARTY_IMPORT.FILE_HASH, fileHash)
                .set(COUNTERPARTY_IMPORT.PROVIDED_BY, providedBy.name)
                .set(COUNTERPARTY_IMPORT.STATE, RecordState.RUNNING.name)
                .set(COUNTERPARTY_IMPORT.STARTED_AT, at)
                .set(COUNTERPARTY_IMPORT.CREATED_AT, at)
                .set(COUNTERPARTY_IMPORT.UPDATED_AT, at)
                .set(COUNTERPARTY_IMPORT.VERSION, 0L)
                .onConflict(COUNTERPARTY_IMPORT.TENANT_ID, COUNTERPARTY_IMPORT.DRY_RUN_ID)
                .doNothing()
                .execute()
        if (inserted == 0) return null
        return ImportRecordData(
            importId,
            entity,
            actor,
            dryRunId,
            fileHash,
            providedBy,
            RecordState.RUNNING,
            started,
            null,
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
        rejectedRows: List<RejectedImportRow>,
        recordHash: String,
    ) {
        val rejected = mapper.createArrayNode()
        rejectedRows.forEach { rejected.add(rejectedNode(mapper, it)) }
        val at = completedAt.atOffset(ZoneOffset.UTC)
        dsl
            .update(COUNTERPARTY_IMPORT)
            .set(COUNTERPARTY_IMPORT.STATE, RecordState.SUCCEEDED.name)
            .set(COUNTERPARTY_IMPORT.COMPLETED_AT, at)
            .set(COUNTERPARTY_IMPORT.APPLIED_COUNT, appliedCount)
            .set(COUNTERPARTY_IMPORT.FAILED_COUNT, failedCount)
            .set(COUNTERPARTY_IMPORT.REJECTED_ROWS, jsonb(rejected.toString()))
            .set(COUNTERPARTY_IMPORT.RECORD_HASH, recordHash)
            .set(COUNTERPARTY_IMPORT.UPDATED_AT, at)
            .set(COUNTERPARTY_IMPORT.VERSION, COUNTERPARTY_IMPORT.VERSION.plus(1L))
            .where(COUNTERPARTY_IMPORT.IMPORT_ID.eq(importId), COUNTERPARTY_IMPORT.STATE.eq(RecordState.RUNNING.name))
            .execute()
    }

    override fun fail(importId: String) {
        failWhere(COUNTERPARTY_IMPORT.IMPORT_ID.eq(importId))
    }

    override fun failRunningBefore(before: Instant): Int =
        failWhere(COUNTERPARTY_IMPORT.STARTED_AT.lt(before.atOffset(ZoneOffset.UTC)))

    private fun failWhere(condition: org.jooq.Condition): Int {
        val at = clock.instant().atOffset(ZoneOffset.UTC)
        return dsl
            .update(COUNTERPARTY_IMPORT)
            .set(COUNTERPARTY_IMPORT.STATE, RecordState.FAILED.name)
            .set(COUNTERPARTY_IMPORT.COMPLETED_AT, at)
            .set(COUNTERPARTY_IMPORT.ERROR, jsonb(failureEnvelope()))
            .set(COUNTERPARTY_IMPORT.UPDATED_AT, at)
            .set(COUNTERPARTY_IMPORT.VERSION, COUNTERPARTY_IMPORT.VERSION.plus(1L))
            .where(condition, COUNTERPARTY_IMPORT.STATE.eq(RecordState.RUNNING.name))
            .execute()
    }

    override fun find(importId: String): ImportRecordData? =
        dsl
            .selectFrom(COUNTERPARTY_IMPORT)
            .where(COUNTERPARTY_IMPORT.IMPORT_ID.eq(importId))
            .fetchOne()
            ?.let(::record)

    override fun findByDryRun(dryRunId: String): ImportRecordData? =
        dsl
            .selectFrom(COUNTERPARTY_IMPORT)
            .where(COUNTERPARTY_IMPORT.DRY_RUN_ID.eq(dryRunId))
            .fetchOne()
            ?.let(::record)

    private fun record(r: org.jooq.Record): ImportRecordData {
        val rec = r.into(COUNTERPARTY_IMPORT)
        return ImportRecordData(
            importId = rec.importId.column("import_id"),
            entity = rec.entityRef.column("entity_ref"),
            actor = rec.actorRef.column("actor_ref"),
            dryRunId = rec.dryRunId.column("dry_run_id"),
            fileHash = rec.fileHash.column("file_hash"),
            providedBy = ImportProvenance.valueOf(rec.providedBy.column("provided_by")),
            state = RecordState.valueOf(rec.state.column("state")),
            startedAt = rec.startedAt.column("started_at").instant(),
            completedAt = rec.completedAt?.instant(),
            appliedCount = rec.appliedCount,
            failedCount = rec.failedCount,
            rejectedRows =
                rec.rejectedRows?.let { json: JSONB ->
                    mapper.readTree(json.data()).values().map(::rejectedOf)
                },
            recordHash = rec.recordHash,
            error = rec.error?.data(),
        )
    }
}
