package com.dynokore.gst.rules.adapters.persistence

import com.dynokore.gst.rules.adapters.persistence.jooq.tables.references.MAPPING_IMPORT_RECORD
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.OffsetDateTime

/** The code of a rejected row; RUL-006 is the only reason a mapping row is rejected (INV-REQ-831 b4). */
const val IMPORT_REJECTED_CODE = "RUL-006"

/** The result of the dry run of an import (INV-REQ-833 b1): every row applied, or the import rejected whole. */
enum class ImportDryRun { CLEAN, REJECTED }

/** One import record as stored: the call's facts, the actor from the token, the outcome and the draft if any. */
data class MappingImportRecordRow(
    val importId: String,
    val importedAt: OffsetDateTime,
    val entityRef: String,
    val actor: String,
    val fileHash: String,
    val fileName: String,
    val rowCount: Int,
    val dryRun: ImportDryRun,
    val appliedCount: Int,
    val rejectedRows: List<Int>,
    val packId: String?,
)

/**
 * The import records of mapping spreadsheet re-imports over `rules.mapping_import_record` (INV-REQ-833), jOOQ only, on
 * the open transaction of the command. The tenant comes from the signed binding; the record is insert-only.
 */
class JooqMappingImportRecordStore(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
) {
    fun insert(record: MappingImportRecordRow) {
        dsl
            .insertInto(MAPPING_IMPORT_RECORD)
            .set(MAPPING_IMPORT_RECORD.TENANT_ID, tenants.current().value)
            .set(MAPPING_IMPORT_RECORD.IMPORT_ID, record.importId)
            .set(MAPPING_IMPORT_RECORD.ENTITY_REF, record.entityRef)
            .set(MAPPING_IMPORT_RECORD.ACTOR, record.actor)
            .set(MAPPING_IMPORT_RECORD.FILE_HASH, record.fileHash)
            .set(MAPPING_IMPORT_RECORD.FILE_NAME, record.fileName)
            .set(MAPPING_IMPORT_RECORD.ROW_COUNT, record.rowCount)
            .set(MAPPING_IMPORT_RECORD.DRY_RUN_RESULT, record.dryRun.name)
            .set(MAPPING_IMPORT_RECORD.APPLIED_COUNT, record.appliedCount)
            .set(MAPPING_IMPORT_RECORD.REJECTED_ROWS, record.rejectedRows.toTypedArray())
            .set(MAPPING_IMPORT_RECORD.REJECTED_CODE, IMPORT_REJECTED_CODE.takeIf { record.rejectedRows.isNotEmpty() })
            .set(MAPPING_IMPORT_RECORD.PACK_ID, record.packId)
            .execute()
    }

    /** The newest clean record of the entity for the file hash: a repeat of that file returns its draft. */
    fun cleanOf(
        entityRef: String,
        fileHash: String,
    ): MappingImportRecordRow? =
        select(where(entityRef, fileHash).and(MAPPING_IMPORT_RECORD.DRY_RUN_RESULT.eq(ImportDryRun.CLEAN.name)), 1)
            .firstOrNull()

    /** The record with this id for the entity and hash; null when none (used as a page cursor). */
    fun find(
        entityRef: String,
        fileHash: String,
        importId: String,
    ): MappingImportRecordRow? =
        select(where(entityRef, fileHash).and(MAPPING_IMPORT_RECORD.IMPORT_ID.eq(importId)), 1).firstOrNull()

    /** The records of the entity with exactly this file hash, newest first, after [after] (exclusive). */
    fun list(
        entityRef: String,
        fileHash: String,
        after: MappingImportRecordRow?,
        limit: Int,
    ): List<MappingImportRecordRow> {
        var condition = where(entityRef, fileHash)
        after?.let {
            condition =
                condition.and(
                    DSL
                        .row(MAPPING_IMPORT_RECORD.IMPORTED_AT, MAPPING_IMPORT_RECORD.IMPORT_ID)
                        .lt(DSL.row(it.importedAt, it.importId)),
                )
        }
        return select(condition, limit)
    }

    private fun where(
        entityRef: String,
        fileHash: String,
    ): Condition =
        MAPPING_IMPORT_RECORD.TENANT_ID
            .eq(tenants.current().value)
            .and(MAPPING_IMPORT_RECORD.ENTITY_REF.eq(entityRef))
            .and(MAPPING_IMPORT_RECORD.FILE_HASH.eq(fileHash))

    private fun select(
        condition: Condition,
        limit: Int,
    ): List<MappingImportRecordRow> =
        dsl
            .selectFrom(MAPPING_IMPORT_RECORD)
            .where(condition)
            .orderBy(MAPPING_IMPORT_RECORD.IMPORTED_AT.desc(), MAPPING_IMPORT_RECORD.IMPORT_ID.desc())
            .limit(limit)
            .fetch()
            .map {
                MappingImportRecordRow(
                    importId = checkNotNull(it.importId),
                    importedAt = checkNotNull(it.importedAt),
                    entityRef = checkNotNull(it.entityRef),
                    actor = checkNotNull(it.actor),
                    fileHash = checkNotNull(it.fileHash),
                    fileName = checkNotNull(it.fileName),
                    rowCount = checkNotNull(it.rowCount),
                    dryRun = ImportDryRun.valueOf(checkNotNull(it.dryRunResult)),
                    appliedCount = checkNotNull(it.appliedCount),
                    rejectedRows = it.rejectedRows?.filterNotNull().orEmpty(),
                    packId = it.packId,
                )
            }
}
