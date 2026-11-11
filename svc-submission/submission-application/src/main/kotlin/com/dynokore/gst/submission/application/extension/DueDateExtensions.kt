package com.dynokore.gst.submission.application.extension

import com.dynokore.platform.tenancykernel.TenantId
import java.time.LocalDate

enum class ExtensionTarget { FILING_DUE_DATE, SUBMISSION_DUE_DATE, BOTH }

enum class ExtensionScopeKind { PERIOD, DOCUMENT_SET, DATE_RANGE }

/**
 * One recorded IRAS extension (INV-REQ-81). [extendedDueDate] is the new submission due date, or the new filing due
 * date when the target is FILING_DUE_DATE; [extendedFilingDueDate] is set only for target BOTH. [scopeRef] is null
 * for PERIOD, the sorted lower-case Doc UUIDs joined by a comma for DOCUMENT_SET and `<dateFrom>/<dateTo>` for
 * DATE_RANGE. [originalDueDate] is the register due date at consume time, null when none applies.
 */
data class DueDateExtension(
    val entityRef: String,
    val periodRef: String,
    val target: ExtensionTarget,
    val scopeKind: ExtensionScopeKind,
    val scopeRef: String?,
    val originalDueDate: LocalDate?,
    val extendedDueDate: LocalDate,
    val extendedFilingDueDate: LocalDate?,
    val irasReference: String,
    val sourceEventId: String,
)

/** Append-only store of extensions: a row is never rewritten. */
interface DueDateExtensions {
    /** False when the event id already has a row; nothing is written then. */
    fun insert(
        tenant: TenantId,
        row: DueDateExtension,
    ): Boolean

    /** The rows of the entity and period in order of recording. */
    fun forPeriod(
        tenant: TenantId,
        entityRef: String,
        periodRef: String,
    ): List<DueDateExtension>
}
