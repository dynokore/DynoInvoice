package com.dynokore.gst.submission.application.extension

import com.dynokore.gst.submission.application.register.RegisterDueDateReader
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantContext
import java.time.LocalDate

/** The scope of a `PeriodExtended`; the lists and dates are set only for the kind that needs them. */
data class PeriodExtendedScope(
    val kind: ExtensionScopeKind,
    val documentRefs: List<String> = emptyList(),
    val dateFrom: LocalDate? = null,
    val dateTo: LocalDate? = null,
)

data class PeriodExtendedData(
    val entityRef: String,
    val period: String,
    val target: ExtensionTarget,
    val newFilingDueDate: LocalDate?,
    val newSubmissionDueDate: LocalDate?,
    val scope: PeriodExtendedScope,
    val irasReference: String,
)

/** Reads the event data JSON of a `PeriodExtended` (the JSON library stays in the adapters, ARC-03). */
fun interface PeriodExtendedDataReader {
    /** @throws ContractViolation when a member is missing or malformed (dead letter at once). */
    fun read(dataJson: String): PeriodExtendedData
}

/**
 * Records an IRAS extension from `PeriodExtended` (INV-REQ-81, INV-REQ-46). One row per event id: a re-delivery is
 * [HandleOutcome.IGNORED]. The original date is read from the register and never changes it. No log line or message
 * carries a field value.
 */
class PeriodExtendedHandler(
    private val tenants: TenantContext,
    private val reader: PeriodExtendedDataReader,
    private val register: RegisterDueDateReader,
    private val store: DueDateExtensions,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        val data = reader.read(event.dataJson)
        val tenant = tenants.current()
        val entries = register.forPeriod(tenant, data.entityRef, data.period)
        val scope = data.scope
        val refs = scope.documentRefs.map { it.lowercase() }.toSortedSet()
        val original =
            when (scope.kind) {
                ExtensionScopeKind.PERIOD -> entries.minOfOrNull { it.dueDate }
                ExtensionScopeKind.DOCUMENT_SET -> entries.filter { it.docUuid.lowercase() in refs }.minOfOrNull { it.dueDate }
                ExtensionScopeKind.DATE_RANGE -> null
            }
        val scopeRef =
            when (scope.kind) {
                ExtensionScopeKind.PERIOD -> null
                ExtensionScopeKind.DOCUMENT_SET -> refs.joinToString(",")
                ExtensionScopeKind.DATE_RANGE -> "${scope.dateFrom}/${scope.dateTo}"
            }
        val extended =
            when (data.target) {
                ExtensionTarget.FILING_DUE_DATE -> data.newFilingDueDate
                ExtensionTarget.SUBMISSION_DUE_DATE, ExtensionTarget.BOTH -> data.newSubmissionDueDate
            } ?: throw ContractViolation("PeriodExtended: the new date of the target is missing")
        if (data.target == ExtensionTarget.BOTH && data.newFilingDueDate == null) {
            throw ContractViolation("PeriodExtended: the new filing date of target BOTH is missing")
        }
        val row =
            DueDateExtension(
                entityRef = data.entityRef,
                periodRef = data.period,
                target = data.target,
                scopeKind = scope.kind,
                scopeRef = scopeRef,
                originalDueDate = original,
                extendedDueDate = extended,
                extendedFilingDueDate = if (data.target == ExtensionTarget.BOTH) data.newFilingDueDate else null,
                irasReference = data.irasReference,
                sourceEventId = event.id.value,
            )
        return if (store.insert(tenant, row)) HandleOutcome.APPLIED else HandleOutcome.IGNORED
    }
}
