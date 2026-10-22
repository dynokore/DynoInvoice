package com.dynokore.gst.tenancy.application.status

import com.dynokore.gst.tenancy.application.structure.StructureStore
import com.dynokore.gst.tenancy.domain.GstReturn
import com.dynokore.gst.tenancy.domain.Phase
import com.dynokore.gst.tenancy.domain.PhaseAssessment
import com.dynokore.gst.tenancy.domain.PhaseAssessor
import com.dynokore.gst.tenancy.domain.PhaseOutcome
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** The request of assessPhase as the service reads it (contract AssessPhaseRequest, INV-Q-8727 A). */
data class PhaseAssessmentRequest(
    val asOfDate: LocalDate,
    val box4Figures: BigDecimal?,
    val returns: List<ReturnFigures>?,
    val extrapolate: Boolean,
)

data class ReturnFigures(
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val box4Figures: BigDecimal,
)

/** An assessment as stored: the result with every input used (INV-REQ-334 behaviour 5). */
data class StoredAssessment(
    val asOfDate: LocalDate,
    val assessment: PhaseAssessment,
) {
    val phase: Phase get() = assessment.phase
}

/** The stored assessments of the entities of the bound tenant; every method runs in the caller's transaction. */
interface PhaseAssessmentStore {
    /** The latest assessment stored for the entity: a repeat recomputes and supersedes the earlier one. */
    fun latest(entityId: UUID): StoredAssessment?

    fun insert(
        entityId: UUID,
        stored: StoredAssessment,
    )
}

sealed interface AssessOutcome {
    data class Done(
        val stored: StoredAssessment,
    ) : AssessOutcome

    data object NotFound : AssessOutcome

    /** The request breaks the rules of assessPhase; [reason] is safe to show the caller (400). */
    data class Invalid(
        val reason: String,
    ) : AssessOutcome
}

/**
 * assessPhase (INV-REQ-334): reads the entity's GST returns, applies the window table and the extrapolation formula
 * of INV-NOTE-2 section 2.2.1 and stores the result with the inputs used. No window is guessed: returns whose period
 * ends fall in different rows of the table are refused.
 */
class AssessPhaseCommands(
    private val tx: TransactionRunner,
    private val structure: StructureStore,
    private val assessments: PhaseAssessmentStore,
) {
    fun assess(
        entityRef: String,
        request: PhaseAssessmentRequest,
    ): AssessOutcome =
        tx.inTransaction {
            val entity = structure.entity(entityRef) ?: return@inTransaction AssessOutcome.NotFound
            when (val computed = compute(request)) {
                is Computed.Refused -> {
                    AssessOutcome.Invalid(computed.reason)
                }

                is Computed.Result -> {
                    val stored = StoredAssessment(request.asOfDate, computed.assessment)
                    assessments.insert(entity.id, stored)
                    AssessOutcome.Done(stored)
                }
            }
        }

    private sealed interface Computed {
        data class Result(
            val assessment: PhaseAssessment,
        ) : Computed

        data class Refused(
            val reason: String,
        ) : Computed
    }

    private fun compute(request: PhaseAssessmentRequest): Computed {
        val returns = request.returns.orEmpty()
        return when {
            request.box4Figures != null && returns.isNotEmpty() -> {
                Computed.Refused("send either box4Figures or returns, not both")
            }

            request.box4Figures == null && returns.isEmpty() -> {
                Computed.Refused("send box4Figures or returns")
            }

            request.box4Figures != null -> {
                if (request.extrapolate) {
                    Computed.Refused("extrapolate needs returns: box4Figures carries no days covered")
                } else {
                    Computed.Result(PhaseAssessor.assessFigure(request.box4Figures))
                }
            }

            else -> {
                fromReturns(returns, request.extrapolate)
            }
        }
    }

    private fun fromReturns(
        figures: List<ReturnFigures>,
        extrapolate: Boolean,
    ): Computed {
        if (figures.any { it.periodEnd.isBefore(it.periodStart) }) {
            return Computed.Refused("a return period must end on or after it starts")
        }
        val returns = figures.map { GstReturn(it.periodStart, it.periodEnd, it.box4Figures) }
        return when (val outcome = PhaseAssessor.assess(returns, extrapolate)) {
            is PhaseOutcome.Assessed -> {
                Computed.Result(outcome.assessment)
            }

            PhaseOutcome.Outstanding -> {
                Computed.Refused(
                    "no return ends in calendar year 2025: the implementation date is outstanding until IRAS " +
                        "notifies it",
                )
            }

            is PhaseOutcome.MixedPeriodEndMonths -> {
                Computed.Refused(
                    "the returns end in different rows of the window table " +
                        "(months ${outcome.months.joinToString()}): no window applies",
                )
            }

            PhaseOutcome.NoDaysCovered -> {
                Computed.Refused("extrapolation was chosen but the returns cover no day")
            }
        }
    }
}
