package com.dynokore.gst.submission.application.register

import com.dynokore.gst.submission.domain.metrics.RowKind
import com.dynokore.gst.submission.domain.register.ListFacts
import com.dynokore.platform.tenancykernel.TenantId
import java.time.LocalDate

/** One obligation register entry as the writer sets it (INV-REQ-975, INV-Q-16315 A). */
data class RegisterEntry(
    val docUuid: String,
    val entityRef: String,
    val periodRef: String,
    val dueDate: LocalDate,
    val inScope: Boolean,
    val kind: RowKind,
    val facts: ListFacts,
    val sourceVersion: Int,
)

enum class UpsertResult { INSERTED, UPDATED, UNCHANGED }

/** Creates or updates the entry of a document; a row changes only for a higher source version. */
fun interface RegisterEntryStore {
    fun upsert(
        tenant: TenantId,
        entry: RegisterEntry,
    ): UpsertResult
}

/** The filing due date and the recorded filing dates of an entity and period. */
data class FilingDeadlineInputs(
    val filingDueDate: LocalDate,
    val filedOn: List<LocalDate>,
)

/** Open design point: no production implementation yet. Null when the period is not known. */
fun interface FilingDeadlineSource {
    fun inputs(
        tenant: TenantId,
        entityRef: String,
        periodRef: String,
    ): FilingDeadlineInputs?
}

/** Open design point: no production implementation yet. Null when the document has no register kind. */
fun interface DocumentKindResolver {
    fun kindOf(data: CanonicalVersionCreatedData): RowKind?
}
