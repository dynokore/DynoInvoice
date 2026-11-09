package com.dynokore.gst.masterdata.adapters.http

import com.dynokore.gst.masterdata.adapters.permission.OperationPermissionKeys
import com.dynokore.gst.masterdata.application.gapreport.RecordState
import com.dynokore.gst.masterdata.application.permission.TenantPermission
import com.dynokore.gst.masterdata.application.rate.ImportRates
import com.dynokore.gst.masterdata.application.rate.ImportRatesCommand
import com.dynokore.gst.masterdata.application.rate.ImportRatesOutcome
import com.dynokore.gst.masterdata.application.rate.RateImportRecord
import com.dynokore.gst.masterdata.application.rate.RateRow
import com.dynokore.gst.masterdata.application.rate.ReadRateImportRecord
import com.dynokore.gst.masterdata.contract.model.ErrorEnvelope
import com.dynokore.gst.masterdata.contract.model.ImportRatesRequest
import com.dynokore.gst.masterdata.contract.model.ImportRecord
import com.dynokore.gst.masterdata.contract.model.ImportRecordDateRange
import com.dynokore.gst.masterdata.contract.model.ImportResult
import com.dynokore.gst.masterdata.contract.model.OperationState
import com.dynokore.gst.masterdata.contract.model.RejectedRow
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.tenancykernel.TenantContext
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import tools.jackson.databind.ObjectMapper
import java.time.ZoneOffset
import java.util.UUID

/**
 * `importRates` and the rate path of `getImportRecord` (INV-T-18775, INV-REQ-506, INV-REQ-833). The permission
 * `exchange-rate.import` is decided first through the replica (403 SRT-004, nothing changes); then the entity is
 * required (`entityRef` present and not blank, else 400 SRT-001); the use case decides the election and the approval
 * on every row date. The command carries `rangeFrom` = `dateRange.from` and no basis. A finished load answers 201
 * with the `ImportResult`; a load held beyond the wait answers 202 with the RUNNING `ImportRecord`; an exception of
 * the work is answered with the API-20 internal error envelope and the record is FAILED.
 */
@Suppress("LongParameterList") // the ports of one controller
class RateImport(
    private val import: ImportRates,
    private val reader: ReadRateImportRecord,
    private val permission: TenantPermission,
    private val tenants: TenantContext,
    private val mapper: ObjectMapper,
) {
    /**
     * The `ImportRecord` of a rate import of the caller, or null when the caller has no such record (the counterparty
     * path then runs). The caller must hold `exchange-rate.import` (403 SRT-004).
     */
    fun readRecord(importId: String): ResponseEntity<ImportRecord>? {
        val actor = tenants.currentActor().orEmpty()
        val record = reader.handle(importId, actor) ?: return null
        if (!permission.holds(actor, OperationPermissionKeys.EXCHANGE_RATE_IMPORT)) {
            throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
        }
        return ResponseEntity.ok(importRecord(record))
    }

    @Suppress("ThrowsCount") // permission, entity, then the use case's refusal: each a distinct API refusal
    fun handle(request: ImportRatesRequest): ResponseEntity<ImportResult> {
        val actor = tenants.currentActor().orEmpty()
        if (!permission.holds(actor, OperationPermissionKeys.EXCHANGE_RATE_IMPORT)) {
            throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
        }
        val entityRef =
            request.entityRef?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION, "entityRef is required")
        val rows =
            request.rows.orEmpty().mapIndexed { index, row ->
                RateRow(index + 1, row.currency, QUOTE_CURRENCY, row.date, row.rate.toPlainString())
            }
        val importId = "IMP-" + UUID.randomUUID()
        val command =
            ImportRatesCommand(
                entity = entityRef,
                actor = actor,
                source = request.source,
                rangeFrom = request.dateRange.from,
                rangeTo = request.dateRange.to,
                importId = importId,
                fileHash = request.fileHash.orEmpty(),
                rowCount = request.rowCount ?: rows.size,
                rows = rows,
            )
        val outcome =
            try {
                import.handle(command)
            } catch (
                @Suppress("TooGenericExceptionCaught") _: Exception,
            ) {
                // the record is FAILED; the caller gets the API-20 envelope, no detail (API-22)
                throw ApiRefusal(ClosedErrorCode.INTERNAL_ERROR)
            }
        RateWireCodes.refusal(outcome)?.let { throw it }
        return when (outcome) {
            is ImportRatesOutcome.Loaded -> {
                ResponseEntity.status(HttpStatus.CREATED).body(
                    ImportResult(importId, outcome.stored, outcome.rejected.size),
                )
            }

            // refused above by RateWireCodes.refusal; listed so that a new member fails to compile here
            is ImportRatesOutcome.Running -> {
                throw LongCommandAccepted(
                    importRecord(outcome.record),
                    "/internal/v1/masterdata/imports/${outcome.record.importId}",
                    RETRY_AFTER_SECONDS,
                )
            }

            ImportRatesOutcome.SourceNotElected,
            ImportRatesOutcome.SourceNotApproved,
            ImportRatesOutcome.RowCountMismatch,
            -> {
                error("refusal outcome without a wire code")
            }
        }
    }

    private fun importRecord(record: RateImportRecord): ImportRecord =
        ImportRecord(
            importId = record.importId,
            importKind = ImportRecord.ImportKind.RATES,
            state = OperationState.valueOf(record.state.name),
            startedAt = record.startedAt.atOffset(ZoneOffset.UTC),
            actorRef = record.actor,
            completedAt = record.completedAt?.atOffset(ZoneOffset.UTC),
            source = record.source,
            dateRange = ImportRecordDateRange(record.rangeFrom, record.rangeTo),
            result =
                if (record.state == RecordState.SUCCEEDED) {
                    ImportResult(record.importId, checkNotNull(record.appliedCount), checkNotNull(record.failedCount))
                } else {
                    null
                },
            rejectedRows =
                record.rejectedRows?.map {
                    val code = RateWireCodes.rowCode(it.reason)
                    RejectedRow(it.row, code.code, code.condition, it.field)
                },
            error = record.error?.let { mapper.readValue(it, ErrorEnvelope::class.java) },
        )

    private companion object {
        const val QUOTE_CURRENCY = "SGD"
        const val RETRY_AFTER_SECONDS = 2L
    }
}
