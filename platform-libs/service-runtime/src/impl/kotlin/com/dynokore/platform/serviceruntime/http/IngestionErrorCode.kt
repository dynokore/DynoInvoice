package com.dynokore.platform.serviceruntime.http

/**
 * The ingestion refusals ING-006, ING-002, ING-022, ING-018 and ING-014 with their catalogue codes (platform-contracts
 * `catalogue/errors/error-codes.yaml`; coordinator rulings INV-Q-8795 A, INV-Q-8797 A, INV-Q-11260 A and
 * INV-Q-11264 A), mapped in one place beside [PolicyErrorCode], [TenancyErrorCode] and [MasterdataErrorCode]
 * (ARC-19, CS-14) so that a service renders them with [Problems.renderIngestion] and writes no code of its own.
 * The title is the row's `condition`, the owner the row's `owner`, the disposition the row's `disposition`; the status is the 422 that INV-Q-1841 ruled for
 * ING-006 and `intakeAggregationBatch` declares, the 409 of ING-002 (INV-REQ-434 item 5), the 422 of ING-022 and the 422 of ING-018 (INV-REQ-435; INV-Q-1841 rules 422
 * for a well-formed but semantically rejectable call; the ruling that split ING-018 out of INV-T-14463 added it).
 * Exactly these five entries exist: AGGREGATE_TYPE_NOT_PERMITTED, SOURCE_REVISION_LOWER,
 * AGGREGATE_NUMBER_NOT_BUILDABLE, CROSSES_PERIOD_BOUNDARY and RECEIPT_REPLACED_AFTER_TRANSMISSION (ING-014, 409,
 * owner Finance; INV-REQ-440, declared by the aggregation operation `excludeReplacedReceipt`; the answered design
 * questions of INV-T-4980, breakdown tooling `gap4980b-gap.md`); no other ING code is added. The test of this
 * enum compares the code, title and owner with the pinned snapshot.
 */
enum class IngestionErrorCode(
    val code: String,
    val httpStatus: Int,
    val title: String,
    val owner: Owner,
    val disposition: String,
) {
    AGGREGATE_TYPE_NOT_PERMITTED(
        "ING-006",
        HttpStatusCodes.UNPROCESSABLE,
        "aggregateType not POS, STI or PCP, or contradicting direction (C-3; extended 25 Sep 2026)",
        Owner.INTEGRATION,
        "TERMINAL",
    ),
    SOURCE_REVISION_LOWER(
        "ING-002",
        HttpStatusCodes.CONFLICT,
        "sourceRevision lower than recorded",
        Owner.INTEGRATION,
        "TERMINAL",
    ),
    AGGREGATE_NUMBER_NOT_BUILDABLE(
        "ING-022",
        HttpStatusCodes.UNPROCESSABLE,
        "Aggregate number cannot be built: the number would exceed 64 characters, or the entity or business unit has no registered short code of 1 to 10 characters A-Z 0-9 in the structure; nothing is stored (HTTP 422; INV-REQ-436, INV-REQ-850 b7, INV-Q-11264)",
        Owner.INTEGRATION,
        "TERMINAL",
    ),
    CROSSES_PERIOD_BOUNDARY(
        "ING-018",
        HttpStatusCodes.UNPROCESSABLE,
        "Pre-aggregated call whose period crosses a prescribed-period boundary (FR-AE.49). Added 25 Sep 2026",
        Owner.INTEGRATION,
        "TERMINAL",
    ),
    RECEIPT_REPLACED_AFTER_TRANSMISSION(
        "ING-014",
        HttpStatusCodes.CONFLICT,
        "Simplified tax invoice replaced by a full tax invoice after its aggregate was transmitted (§33.9)",
        Owner.FINANCE,
        "MANUAL",
    ),
    ;

    /** The catalogue `owner` text of the row (error-codes.yaml). */
    enum class Owner(
        val text: String,
    ) {
        INTEGRATION("Integration"),
        FINANCE("Finance"),
    }
}
