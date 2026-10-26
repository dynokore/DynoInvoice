package com.dynokore.gst.canonical.domain.derivation

/**
 * The derivation steps 1 to 8 of INV-REQ-479 behavior 1, in the order they run. Each label is the wording of that
 * behavior. Steps 9 to 11 (submission type and due date, MDE payload) have no member: they never influence the gate.
 */
enum class DerivationStep(
    val label: String,
) {
    DOCUMENT_FUNCTION("documentFunction"),
    TRANSACTION_DATE("transactionDate"),
    PRESCRIBED_PERIOD_REF("prescribedPeriodRef"),
    PACK_VERSIONS("pack versions"),
    LINE_SCOPE_AND_CATEGORY("line scopeVerdict and gstCategoryCode"),
    DOCUMENT_SCOPE_VERDICT("document scopeVerdict"),
    SUBTOTALS("subtotals"),
    PARTY_IDENTIFIERS("party identifiers"),
}

/** The remote services whose unavailability holds a document (INV-REQ-479 behavior 4). */
enum class Dependency {
    RULES_SVC,
    MASTERDATA_SVC,
}

/** The outcome of one derivation step. */
sealed interface StepOutcome {
    /** The step finished. */
    data object Complete : StepOutcome

    /** The step could not run because [dependency] is unavailable. */
    data class Unavailable(
        val dependency: Dependency,
    ) : StepOutcome

    /** The step's own decision returned the hold with catalogue code [code]. */
    data class HeldBy(
        val code: String,
    ) : StepOutcome
}

/** Whether a document may leave INGESTED. */
sealed interface GateDecision {
    data object Proceed : GateDecision

    /** [reasons] are catalogue hold codes, one per step that is not complete, in step order, each code once. */
    data class Held(
        val reasons: List<String>,
    ) : GateDecision
}
