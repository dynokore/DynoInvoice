package com.dynokore.gst.reconciliation.application.run

import com.dynokore.gst.reconciliation.domain.boxes.Direction
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

data class DocumentLineFacts(
    val lineNumber: Int,
    val taxCode: String,
    val lineAmount: BigDecimal,
    val taxAmount: BigDecimal,
)

data class DocumentVersionFacts(
    val docUuid: String,
    val version: Int,
    val docsequence: Long,
    val periodRef: String,
    val transactionDate: LocalDate,
    val scopeVerdict: String,
    val notSubmittableReason: String?,
    val direction: Direction,
    val currency: String,
    val exchangeRate: BigDecimal?,
    val sgdTotalExcludingTax: BigDecimal?,
    val sgdTotalTax: BigDecimal?,
    val lines: List<DocumentLineFacts>,
)

/**
 * Writer port of the run read model that the consumers fill. Each method returns true when it applied
 * a change and false for an idempotent repeat or a stale event that changed nothing.
 */
interface RunReadModelWriter {
    fun recordVersion(
        tenant: String,
        entityRef: String,
        facts: DocumentVersionFacts,
    ): Boolean

    fun reassignPeriod(
        tenant: String,
        entityRef: String,
        docUuid: String,
        docsequence: Long,
        newPeriodRef: String,
    ): Boolean

    fun recordAcknowledgement(
        tenant: String,
        docUuid: String,
        version: Int,
        acknowledgementId: String,
        acknowledgedAt: Instant,
    ): Boolean
}
