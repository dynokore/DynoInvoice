package com.dynokore.gst.rules.domain.coding

/** The direction of a document for the entity; absent means [SALE] (the contract's `direction`). */
enum class Direction { SALE, PURCHASE }

/** The submission types the scenario pack may assign (INV-REQ-415). */
enum class SubmissionType { TYPE_1A, TYPE_1B, TYPE_2, TYPE_3 }

/** The assignment of one self-billing party: only the direction and the type leave rules-svc (INV-Q-6988). */
data class SelfBillingAssignment(
    val direction: Direction,
    val submissionType: SubmissionType,
)

enum class Handling { HANDLED, PENDING_QUESTION }

data class SchemeTagSide(
    val tag: String,
    val side: Side,
    val handling: Handling,
)

enum class Side { SUPPLY, PURCHASE }

enum class RateBasis { FIXED, PREVAILING }

/** What a handler decides for a (tag, side) pair (INV-REQ-1805): category code, rate basis, IBT-022 statement. */
data class Treatment(
    val tag: String,
    val side: Side,
    val categoryCode: String,
    val rateBasis: RateBasis,
    val rate: String?,
    val counterpartyApprovedUnder: Set<String>,
    val writtenAgreement: String?,
    val ibt022Statement: String?,
)

/**
 * The decision sections of the scenario pack of the version resolved by transaction date (INV-REQ-416, INV-REQ-1805).
 * Every value here is read from pack data: the self-billing assignment, the handled (tag, side) pairs and the
 * treatments are not constants of the engine.
 */
data class ScenarioData(
    val customerCumIssuer: SelfBillingAssignment,
    val supplierCumRecipient: SelfBillingAssignment,
    val schemeTagSides: List<SchemeTagSide>,
    val treatments: List<Treatment>,
)
