package com.dynokore.gst.canonical.application.document

import com.dynokore.gst.canonical.domain.dating.DatingDecision
import com.dynokore.gst.canonical.domain.dating.DatingInput
import com.dynokore.gst.canonical.domain.dating.PeriodAssignment
import com.dynokore.gst.canonical.domain.dating.PeriodDecision
import com.dynokore.gst.canonical.domain.dating.PostingMoment
import com.dynokore.gst.canonical.domain.dating.SINGAPORE
import com.dynokore.gst.canonical.domain.dating.TransactionDating
import com.dynokore.gst.canonical.domain.derivation.Dependency
import com.dynokore.gst.canonical.domain.derivation.DerivationGate
import com.dynokore.gst.canonical.domain.derivation.DerivationStep
import com.dynokore.gst.canonical.domain.derivation.GateDecision
import com.dynokore.gst.canonical.domain.derivation.StepOutcome
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import com.dynokore.gst.canonical.domain.dating.Direction as DatingDirection
import com.dynokore.gst.canonical.domain.document.Direction as DocumentDirection

/** The record is refused with a stable error [code] (for example SRT-001); nothing is stored. */
class RecordRefused(
    val code: String,
) : IllegalStateException(code)

/** Two or more period spans cover the transaction date; [failureCode] is SRT-010; nothing is stored. */
class PeriodInconsistent(
    val failureCode: String,
) : IllegalStateException(failureCode)

/**
 * Builds the result text of a record: derivation steps 2 to 6 (steps 1, 7 and 8 have no input in this slice and are
 * complete), then the gate. A held answer follows the contract's 202 example: empty prescribedPeriodRef when
 * unassigned, scopeVerdict SUBMITTABLE (provisional, re-derived on release), reasons listing the hold codes.
 */
class RecordDocumentResultBuilder(
    private val periods: PeriodSpans,
    private val elections: PurchaseElections,
    private val scope: ScopeEvaluation,
    private val versions: DocumentVersions,
    private val mapper: ResultJson,
) : DocumentResultBuilder {
    override fun build(
        command: RecordDocumentCommand,
        docUuid: UUID,
    ): String {
        val dating = TransactionDating.decide(datingInput(command))
        if (dating is DatingDecision.PostingTimestampRequired) {
            throw RecordRefused(DatingDecision.PostingTimestampRequired.refusalCode)
        }
        val date: LocalDate? = (dating as? DatingDecision.Dated)?.transactionDate
        val outcomes =
            DerivationStep.entries.associateWithTo(linkedMapOf<DerivationStep, StepOutcome>()) {
                StepOutcome.Complete
            }
        if (dating is DatingDecision.Held) {
            outcomes[DerivationStep.TRANSACTION_DATE] =
                StepOutcome.HeldBy(dating.code.code)
        }
        val periodRef = date?.let { assignPeriod(command, it, outcomes) }
        val answered = date?.let { evaluateScope(command, periodRef, it, outcomes) }
        val held = DerivationGate.decide(outcomes) as? GateDecision.Held
        val result = linkedMapOf<String, Any?>()
        result["docUuid"] = docUuid.toString()
        result["version"] = versions.nextVersion(docUuid)
        result["scopeVerdict"] = if (held == null) answered ?: PROVISIONAL_VERDICT else PROVISIONAL_VERDICT
        result["transactionDate"] = (date ?: command.transactionDate).toString()
        result["prescribedPeriodRef"] = periodRef.orEmpty()
        if (held != null) result["reasons"] = held.reasons.map { reasonOf(it) }
        return mapper.toJson(result)
    }

    private fun assignPeriod(
        command: RecordDocumentCommand,
        date: LocalDate,
        outcomes: MutableMap<DerivationStep, StepOutcome>,
    ): String? =
        when (val period = PeriodAssignment.assign(date, periods.spansOf(command.tenantId, command.entityRef))) {
            is PeriodDecision.Assigned -> {
                period.periodRef
            }

            is PeriodDecision.Held -> {
                outcomes[DerivationStep.PRESCRIBED_PERIOD_REF] = StepOutcome.HeldBy(period.code.code)
                null
            }

            is PeriodDecision.Inconsistent -> {
                throw PeriodInconsistent(period.failureCode)
            }
        }

    private fun evaluateScope(
        command: RecordDocumentCommand,
        periodRef: String?,
        date: LocalDate,
        outcomes: MutableMap<DerivationStep, StepOutcome>,
    ): String? =
        when (val answer = scope.evaluate(command, periodRef, date)) {
            is ScopeAnswer.Verdict -> {
                answer.scopeVerdict
            }

            ScopeAnswer.Unavailable -> {
                outcomes[DerivationStep.PACK_VERSIONS] = StepOutcome.Unavailable(Dependency.RULES_SVC)
                outcomes[DerivationStep.LINE_SCOPE_AND_CATEGORY] = StepOutcome.Unavailable(Dependency.RULES_SVC)
                null
            }
        }

    private fun reasonOf(code: String): Map<String, String> =
        mapOf(
            "code" to code,
            "field" to "/",
            "message" to messageOf(code),
        )

    private fun messageOf(code: String): String =
        when (code) {
            "PER-009" -> "No prescribed period covers the transaction date"
            "PER-011" -> "No purchase date basis election is in force"
            "ING-016" -> "Derivation incomplete; rules-svc unavailable"
            else -> "Derivation held: $code"
        }

    private fun datingInput(command: RecordDocumentCommand): DatingInput {
        val direction =
            when (checkNotNull(command.direction) { "direction is required to date a document" }) {
                DocumentDirection.SALE -> DatingDirection.SALE
                DocumentDirection.PURCHASE -> DatingDirection.PURCHASE
            }
        val election =
            if (direction == DatingDirection.PURCHASE) {
                elections.electionOf(command.tenantId, command.entityRef, command.transactionDate)
            } else {
                null
            }
        val posting =
            command.postingTimestamp?.let { PostingMoment.Zoned(it) }
                ?: command.localPostingTimestamp?.let { PostingMoment.Zoneless(it) }
        val zone: ZoneId = command.sourceTimeZone?.let { ZoneId.of(it) } ?: SINGAPORE
        return DatingInput(direction, command.invoiceBacked, command.transactionDate, posting, zone, election)
    }

    private companion object {
        const val PROVISIONAL_VERDICT = "SUBMITTABLE"
    }
}
