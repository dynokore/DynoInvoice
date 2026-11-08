package com.dynokore.gst.masterdata.adapters.http

import com.dynokore.gst.masterdata.application.gapreport.RecordState
import com.dynokore.gst.masterdata.application.imports.DryRunCounterpartyImport
import com.dynokore.gst.masterdata.application.imports.DryRunRecord
import com.dynokore.gst.masterdata.application.imports.ImportCommand
import com.dynokore.gst.masterdata.application.imports.ImportCounterparties
import com.dynokore.gst.masterdata.application.imports.ImportDryRunRefused
import com.dynokore.gst.masterdata.application.imports.ImportProvenance
import com.dynokore.gst.masterdata.application.imports.ImportRecordData
import com.dynokore.gst.masterdata.application.imports.ReadDryRun
import com.dynokore.gst.masterdata.application.imports.ReadImportRecord
import com.dynokore.gst.masterdata.application.permission.MasterdataPermissions
import com.dynokore.gst.masterdata.application.permission.TenantPermission
import com.dynokore.gst.masterdata.contract.api.ImportApi
import com.dynokore.gst.masterdata.contract.model.CounterpartyDryRunRejectedRow
import com.dynokore.gst.masterdata.contract.model.CounterpartyDryRunRequest
import com.dynokore.gst.masterdata.contract.model.CounterpartyDryRunResult
import com.dynokore.gst.masterdata.contract.model.CounterpartyDryRunResultDuplicatesInner
import com.dynokore.gst.masterdata.contract.model.CounterpartyDryRunResultPreview
import com.dynokore.gst.masterdata.contract.model.CounterpartyDryRunResultPreviewChangesInner
import com.dynokore.gst.masterdata.contract.model.CounterpartyDryRunStatus
import com.dynokore.gst.masterdata.contract.model.EnterRateRequest
import com.dynokore.gst.masterdata.contract.model.ErrorEnvelope
import com.dynokore.gst.masterdata.contract.model.ExchangeRate
import com.dynokore.gst.masterdata.contract.model.ImportCounterpartiesRequest
import com.dynokore.gst.masterdata.contract.model.ImportRecord
import com.dynokore.gst.masterdata.contract.model.ImportResult
import com.dynokore.gst.masterdata.contract.model.OperationState
import com.dynokore.gst.masterdata.contract.model.RejectedRow
import com.dynokore.gst.masterdata.domain.counterparty.ImportAnalysis
import com.dynokore.gst.masterdata.domain.counterparty.ImportRow
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.MasterdataErrorCode
import com.dynokore.platform.tenancykernel.TenantContext
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper
import java.time.ZoneOffset
import java.util.UUID

/**
 * `dryRunCounterpartyImport`, `getCounterpartyImportDryRun`, `importCounterparties` and `getImportRecord`
 * (INV-T-4944, INV-REQ-830, INV-REQ-833). The permission `counterparty-import.run` is the first statement of each
 * operation (INV-Q-17215 A). The actor of an import is the token's principal, never a request field. A long command
 * that outlasts `x-timeout` answers 202 with `Location` and `Retry-After` and the RUNNING record (API-17).
 */
@Suppress("LongParameterList") // the use cases of the four operations
@RestController
// open: the generated API interface is @Validated, so Spring proxies the controller
open class ImportController(
    private val dryRun: DryRunCounterpartyImport,
    private val readDryRun: ReadDryRun,
    private val import: ImportCounterparties,
    private val readImport: ReadImportRecord,
    private val permission: TenantPermission,
    private val tenants: TenantContext,
    private val mapper: ObjectMapper,
    private val rates: ManualRateEntry,
) : ImportApi {
    override fun enterRate(
        idempotencyKey: UUID,
        enterRateRequest: EnterRateRequest,
    ): ResponseEntity<ExchangeRate> = rates.handle(enterRateRequest)

    override fun dryRunCounterpartyImport(
        idempotencyKey: UUID,
        counterpartyDryRunRequest: CounterpartyDryRunRequest,
    ): ResponseEntity<CounterpartyDryRunResult> {
        allowed()
        val request = counterpartyDryRunRequest
        val rows = request.rows ?: CounterpartyChangeMapping.invalid("rows is required")
        if (rows.size > MAX_ROWS) throw ApiRefusal(ClosedErrorCode.REQUEST_TOO_LARGE)
        if (request.rowCount != rows.size) CounterpartyChangeMapping.invalid("rowCount does not match rows")
        val actor = tenants.currentActor().orEmpty()
        val answer =
            dryRun.handle(
                actor,
                request.fileHash,
                rows.map { ImportRow(it.counterpartyRef, it.uen, it.legalName, it.addressLine1, it.postalCode) },
            )
        val status = dryRunStatus(answer.record)
        if (answer.finished) return ResponseEntity.ok(checkNotNull(status.result))
        throw LongCommandAccepted(
            status,
            "/internal/v1/masterdata/counterparties/import/dry-runs/${answer.record.dryRunId}",
            RETRY_AFTER_SECONDS,
        )
    }

    override fun getCounterpartyImportDryRun(dryRunId: String): ResponseEntity<CounterpartyDryRunStatus> {
        allowed()
        val record = readDryRun.handle(dryRunId) ?: throw ApiRefusal(ClosedErrorCode.NOT_FOUND)
        return ResponseEntity.ok(dryRunStatus(record))
    }

    @Suppress("ThrowsCount") // each throw is one distinct API refusal or answer
    override fun importCounterparties(
        idempotencyKey: UUID,
        importCounterpartiesRequest: ImportCounterpartiesRequest,
    ): ResponseEntity<ImportResult> {
        val actor = allowed()
        val request = importCounterpartiesRequest
        val entity =
            request.entityRef?.trim()?.takeIf { it.isNotEmpty() }
                ?: CounterpartyChangeMapping.invalid("entityRef is required")
        val provided =
            request.providedBy?.let { ImportProvenance.valueOf(it.name) }
                ?: CounterpartyChangeMapping.invalid("providedBy is required")
        val answer =
            try {
                import.handle(ImportCommand(entity, actor, request.dryRunId, request.fileHash, provided))
            } catch (_: ImportDryRunRefused) {
                throw MasterdataRefusal(MasterdataErrorCode.IMPORT_DRY_RUN_REFUSED)
            } catch (
                @Suppress("TooGenericExceptionCaught") _: Exception,
            ) {
                // the record is FAILED and nothing was written; the caller gets the API-20 envelope, no detail (API-22)
                throw ApiRefusal(ClosedErrorCode.INTERNAL_ERROR)
            }
        val record = answer.record
        return when (record.state) {
            RecordState.SUCCEEDED -> {
                ResponseEntity.status(HttpStatus.CREATED).body(
                    ImportResult(record.importId, checkNotNull(record.appliedCount), checkNotNull(record.failedCount)),
                )
            }

            RecordState.FAILED -> {
                throw storedFailure(checkNotNull(record.error))
            }

            RecordState.RUNNING -> {
                throw LongCommandAccepted(
                    importRecord(record),
                    "/internal/v1/masterdata/imports/${record.importId}",
                    RETRY_AFTER_SECONDS,
                )
            }
        }
    }

    override fun getImportRecord(importId: String): ResponseEntity<ImportRecord> {
        val actor = allowed()
        val record = readImport.handle(importId, actor) ?: throw ApiRefusal(ClosedErrorCode.NOT_FOUND)
        return ResponseEntity.ok(importRecord(record))
    }

    /** The envelope stored with a FAILED record; a code the closed set does not know answers INTERNAL_ERROR. */
    private fun storedFailure(envelope: String): StoredFailure {
        val code = mapper.readTree(envelope).path("code").asString("")
        val known = ClosedErrorCode.entries.firstOrNull { it.code == code } ?: ClosedErrorCode.INTERNAL_ERROR
        return StoredFailure(known, envelope)
    }

    private fun allowed(): String {
        val actor = tenants.currentActor().orEmpty()
        if (!permission.holds(actor, MasterdataPermissions.COUNTERPARTY_IMPORT_RUN)) {
            throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
        }
        return actor
    }

    private fun dryRunStatus(record: DryRunRecord): CounterpartyDryRunStatus =
        CounterpartyDryRunStatus(
            dryRunId = record.dryRunId,
            state = OperationState.valueOf(record.state.name),
            startedAt = record.startedAt.atOffset(ZoneOffset.UTC),
            completedAt = record.completedAt?.atOffset(ZoneOffset.UTC),
            result = record.result?.takeIf { record.state == RecordState.SUCCEEDED }?.let { dryRunResult(record, it) },
            error = record.error?.let { mapper.readValue(it, ErrorEnvelope::class.java) },
        )

    private fun dryRunResult(
        record: DryRunRecord,
        a: ImportAnalysis,
    ) = CounterpartyDryRunResult(
        dryRunId = record.dryRunId,
        fileHash = record.fileHash,
        rowCount = record.rowCount,
        validCount = a.validCount,
        rejectedRows = a.rejected.map { CounterpartyDryRunRejectedRow(it.row, it.code, it.message, it.field) },
        duplicates =
            a.duplicates.map {
                CounterpartyDryRunResultDuplicatesInner(
                    it.uen,
                    it.rows,
                    it.existingCounterpartyRef,
                )
            },
        preview =
            CounterpartyDryRunResultPreview(
                a.createCount,
                a.updateCount,
                a.unchangedCount,
                a.changes.map {
                    CounterpartyDryRunResultPreviewChangesInner(
                        uen = it.uen,
                        field = it.field,
                        newValue = it.newValue,
                        counterpartyRef = it.counterpartyRef,
                        oldValue = it.oldValue,
                    )
                },
            ),
    )

    private fun importRecord(record: ImportRecordData): ImportRecord =
        ImportRecord(
            importId = record.importId,
            importKind = ImportRecord.ImportKind.COUNTERPARTIES,
            state = OperationState.valueOf(record.state.name),
            startedAt = record.startedAt.atOffset(ZoneOffset.UTC),
            actorRef = record.actor,
            completedAt = record.completedAt?.atOffset(ZoneOffset.UTC),
            fileHash = record.fileHash,
            dryRunId = record.dryRunId,
            result =
                if (record.state == RecordState.SUCCEEDED) {
                    ImportResult(record.importId, checkNotNull(record.appliedCount), checkNotNull(record.failedCount))
                } else {
                    null
                },
            rejectedRows = record.rejectedRows?.map { RejectedRow(it.row, it.code, it.message, it.field) },
            error = record.error?.let { mapper.readValue(it, ErrorEnvelope::class.java) },
        )

    private companion object {
        const val MAX_ROWS = 5000
        const val RETRY_AFTER_SECONDS = 2L
    }
}
