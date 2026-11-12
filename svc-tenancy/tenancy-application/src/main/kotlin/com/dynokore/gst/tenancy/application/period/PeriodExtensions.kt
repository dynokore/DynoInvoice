package com.dynokore.gst.tenancy.application.period

import com.dynokore.gst.tenancy.application.election.CallerSource
import com.dynokore.gst.tenancy.application.structure.EntityRecord
import com.dynokore.gst.tenancy.application.structure.StructureStore
import com.dynokore.gst.tenancy.domain.PeriodPermissions
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Which due date an extension moves (INV-REQ-86): the members of the contract's `target`. */
enum class ExtensionTarget { FILING_DUE_DATE, SUBMISSION_DUE_DATE, BOTH }

/** What an extension covers (INV-REQ-86): the whole period, a document set or a date range. */
enum class ExtensionScopeKind { PERIOD, DOCUMENT_SET, DATE_RANGE }

data class ExtensionScope(
    val kind: ExtensionScopeKind,
    val documentRefs: List<String> = emptyList(),
    val dateFrom: LocalDate? = null,
    val dateTo: LocalDate? = null,
)

/**
 * A recorded IRAS extension as the request carries it (INV-T-16120): [newDueDate] is the new absolute date of the
 * named target (for BOTH, the filing due date); [newSubmissionDueDate] is present exactly when the target is BOTH.
 */
data class ExtensionRequest(
    val target: ExtensionTarget,
    val newDueDate: LocalDate,
    val newSubmissionDueDate: LocalDate?,
    val scope: ExtensionScope,
    val irasReference: String,
    val evidenceRef: String,
) {
    /** The new filing due date, when the target includes the filing due date. */
    val newFilingDueDate: LocalDate? get() = newDueDate.takeIf { target != ExtensionTarget.SUBMISSION_DUE_DATE }

    /** The new submission due date, when the target includes the submission due date. */
    val newSubmission: LocalDate?
        get() =
            when (target) {
                ExtensionTarget.FILING_DUE_DATE -> null
                ExtensionTarget.SUBMISSION_DUE_DATE -> newDueDate
                ExtensionTarget.BOTH -> newSubmissionDueDate
            }
}

/** The stored extension: the request, the filing due date it replaced, who recorded it and when. */
data class ExtensionRecord(
    val periodRef: String,
    val request: ExtensionRequest,
    val originalFilingDueDate: LocalDate?,
    val recordedBy: String,
    val recordedAt: Instant,
)

/** The extensions of the period records of the bound tenant's entities (insert-only). */
interface PeriodExtensionStore {
    fun forPeriod(
        entityId: UUID,
        periodRef: String,
    ): List<ExtensionRecord>

    /** The period references of the entity that carry at least one extension record. */
    fun extendedPeriods(entityId: UUID): Set<String>

    fun insert(
        entityId: UUID,
        record: ExtensionRecord,
    )
}

/** The PeriodExtended event of a recorded extension (INV-Q-7391). */
data class PeriodExtension(
    val entityRef: String,
    val periodRef: String,
    val request: ExtensionRequest,
)

/** Why a filing due date changed: exactly one of the three causes is set (INV-REQ-81 b4). */
sealed interface DueDateCause {
    /** A recorded IRAS extension. */
    data class Extension(
        val irasReference: String,
    ) : DueDateCause

    /** A calendar pack release (INV-REQ-88). */
    data class Pack(
        val packIdentifier: String,
        val packVersion: String,
        val effectiveDate: LocalDate,
    ) : DueDateCause

    /** A change of the non-business-day rule VP-62 (INV-REQ-1323). */
    data class VariationPoint(
        val variationPoint: String,
        val effectiveDate: LocalDate,
    ) : DueDateCause
}

/** One PeriodDueDateChanged to publish through the outbox in the changing transaction (INV-Q-7391). */
data class PeriodDueDateChange(
    val entityRef: String,
    val periodRef: String,
    val oldFilingDueDate: LocalDate,
    val newFilingDueDate: LocalDate,
    val cause: DueDateCause,
)

interface PeriodEventPublisher {
    fun extended(event: PeriodExtension)

    fun dueDateChanged(
        change: PeriodDueDateChange,
        dueDateVersion: Long,
    )
}

sealed interface ExtensionOutcome {
    data class Done(
        val entityRef: String,
        val periodRef: String,
        val request: ExtensionRequest,
    ) : ExtensionOutcome

    data object NotFound : ExtensionOutcome

    /** 403 SRT-004: the caller does not hold the permission on the entity; nothing is stored. */
    data class Forbidden(
        val reason: String,
    ) : ExtensionOutcome

    /** 400 SRT-001: the request breaks a rule of the contract; nothing is stored and no event is published. */
    data class Invalid(
        val reason: String,
    ) : ExtensionOutcome

    /** 422 POL-014: the IRAS reference is already recorded for the period with other values. */
    data class Refused(
        val reason: String,
    ) : ExtensionOutcome
}

/**
 * recordExtension (INV-REQ-81, INV-REQ-86): the only per-period change of a due date. The caller must hold the
 * extension permission on the entity (decided per request by [PeriodAccess], never by a role name). A request without
 * an IRAS reference or an evidence reference is refused and changes nothing. A filing-due-date part moves that
 * period's due date only and publishes PeriodDueDateChanged; PeriodExtended is published for every recorded extension.
 */
@Suppress("LongParameterList") // one port per collaborator of the use case, as in the other period use cases
class RecordExtension(
    private val tx: TransactionRunner,
    private val structure: StructureStore,
    private val access: PeriodAccess,
    private val caller: CallerSource,
    private val periods: PeriodRecordStore,
    private val extensions: PeriodExtensionStore,
    private val publisher: PeriodEventPublisher,
    private val recomputation: DueDateRecomputation,
    private val now: () -> Instant = Instant::now,
) {
    fun record(
        entityRef: String,
        periodRef: String,
        request: ExtensionRequest,
    ): ExtensionOutcome =
        tx.inTransaction {
            val entity = structure.entity(entityRef) ?: return@inTransaction ExtensionOutcome.NotFound
            access.denial(entityRef, PeriodPermissions.EXTENSION_RECORD)?.let {
                return@inTransaction ExtensionOutcome.Forbidden(it)
            }
            invalidReason(request)?.let { return@inTransaction ExtensionOutcome.Invalid(it) }
            val period =
                periods.inForce(entity.id).firstOrNull { it.periodRef == periodRef }
                    ?: return@inTransaction ExtensionOutcome.NotFound
            val same =
                extensions.forPeriod(entity.id, periodRef).firstOrNull {
                    it.request.irasReference ==
                        request.irasReference
                }
            when {
                same == null -> {
                    store(entity, period, request)
                }

                same.request == request -> {
                    ExtensionOutcome.Done(entityRef, periodRef, request)
                }

                else -> {
                    ExtensionOutcome.Refused(
                        "the IRAS reference is already recorded for the period with other values",
                    )
                }
            }
        }

    /**
     * Stores the record, then sets the filing due date to the date in force (INV-Q-16808): the later of the rule date
     * and the greatest granted date including this record, so an earlier date never moves it back. The period and one
     * PeriodDueDateChanged are written only when that date differs from the stored one.
     */
    private fun store(
        entity: EntityRecord,
        period: StoredPeriod,
        request: ExtensionRequest,
    ): ExtensionOutcome {
        val moves = request.newFilingDueDate
        extensions.insert(
            entity.id,
            ExtensionRecord(period.periodRef, request, moves?.let { period.dueDate }, caller.actor(), now()),
        )
        val inForce = recomputation.dueDateInForce(entity, period, now().atZone(SINGAPORE).toLocalDate())
        if (inForce != period.dueDate) {
            val version = periods.setDueDate(entity.id, period.periodRef, inForce)
            publisher.dueDateChanged(
                PeriodDueDateChange(
                    entity.ref,
                    period.periodRef,
                    period.dueDate,
                    inForce,
                    DueDateCause.Extension(request.irasReference),
                ),
                version,
            )
        }
        publisher.extended(PeriodExtension(entity.ref, period.periodRef, request))
        return ExtensionOutcome.Done(entity.ref, period.periodRef, request)
    }

    /** The first rule of the contract the request breaks, or null (each is a 400 that stores nothing). */
    private fun invalidReason(request: ExtensionRequest): String? = referenceReason(request) ?: scopeReason(request)

    private fun referenceReason(request: ExtensionRequest): String? =
        when {
            request.irasReference.isBlank() -> {
                "the IRAS reference is required"
            }

            request.evidenceRef.isBlank() -> {
                "the evidence reference is required"
            }

            request.target == ExtensionTarget.BOTH && request.newSubmissionDueDate == null -> {
                "target BOTH requires newSubmissionDueDate"
            }

            request.target != ExtensionTarget.BOTH && request.newSubmissionDueDate != null -> {
                "newSubmissionDueDate is allowed only with target BOTH"
            }

            else -> {
                null
            }
        }

    private fun scopeReason(request: ExtensionRequest): String? {
        val scope = request.scope
        return when {
            request.target == ExtensionTarget.FILING_DUE_DATE && scope.kind != ExtensionScopeKind.PERIOD -> {
                "a filing due date extension has the scope kind PERIOD"
            }

            scope.kind == ExtensionScopeKind.DOCUMENT_SET && scope.documentRefs.isEmpty() -> {
                "scope DOCUMENT_SET requires documentRefs"
            }

            scope.kind == ExtensionScopeKind.DATE_RANGE -> {
                rangeReason(scope)
            }

            else -> {
                null
            }
        }
    }

    private fun rangeReason(scope: ExtensionScope): String? {
        val from = scope.dateFrom
        val to = scope.dateTo
        return when {
            from == null || to == null -> "scope DATE_RANGE requires dateFrom and dateTo"
            from.isAfter(to) -> "dateFrom is after dateTo"
            else -> null
        }
    }
}
