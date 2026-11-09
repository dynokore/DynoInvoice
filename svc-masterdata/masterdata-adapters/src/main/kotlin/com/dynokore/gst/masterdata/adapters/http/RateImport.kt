package com.dynokore.gst.masterdata.adapters.http

import com.dynokore.gst.masterdata.adapters.events.RateImportEntity
import com.dynokore.gst.masterdata.adapters.permission.OperationPermissionKeys
import com.dynokore.gst.masterdata.application.permission.TenantPermission
import com.dynokore.gst.masterdata.application.rate.ImportRates
import com.dynokore.gst.masterdata.application.rate.ImportRatesCommand
import com.dynokore.gst.masterdata.application.rate.ImportRatesOutcome
import com.dynokore.gst.masterdata.application.rate.RateRow
import com.dynokore.gst.masterdata.contract.model.ImportRatesRequest
import com.dynokore.gst.masterdata.contract.model.ImportResult
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.tenancykernel.TenantContext
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import java.util.UUID

/**
 * `importRates` (INV-T-18775, INV-REQ-506). The permission `exchange-rate.import` is decided first through the replica
 * (403 SRT-004, nothing changes); then the entity is required (`entityRef` present and not blank, else 400 SRT-001);
 * the use case decides the election and the approval on every row date. The command carries `rangeFrom` =
 * `dateRange.from` and no basis. The load runs synchronously, so a finished load answers 201 (never 202).
 */
class RateImport(
    private val import: ImportRates,
    private val permission: TenantPermission,
    private val tenants: TenantContext,
    private val entity: RateImportEntity,
) {
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
                source = request.source,
                rangeFrom = request.dateRange.from,
                importId = importId,
                fileHash = request.fileHash.orEmpty(),
                rowCount = request.rowCount ?: rows.size,
                rows = rows,
            )
        val outcome = entity.within(entityRef) { import.handle(command) }
        RateWireCodes.refusal(outcome)?.let { throw it }
        return when (outcome) {
            is ImportRatesOutcome.Loaded -> {
                ResponseEntity.status(HttpStatus.CREATED).body(
                    ImportResult(importId, outcome.stored, outcome.rejected.size),
                )
            }

            // refused above by RateWireCodes.refusal; listed so that a new member fails to compile here
            ImportRatesOutcome.SourceNotElected,
            ImportRatesOutcome.SourceNotApproved,
            ImportRatesOutcome.RowCountMismatch,
            -> {
                error("refusal outcome without a wire code")
            }
        }
    }

    private companion object {
        const val QUOTE_CURRENCY = "SGD"
    }
}
