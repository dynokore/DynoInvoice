package com.dynokore.platform.serviceruntime.http

/**
 * The ingestion refusals ING-006, ING-002 and ING-022 with their catalogue codes (platform-contracts
 * `catalogue/errors/error-codes.yaml`; coordinator rulings INV-Q-8795 A, INV-Q-8797 A, INV-Q-11260 A and
 * INV-Q-11264 A), mapped in one place beside [PolicyErrorCode], [TenancyErrorCode] and [MasterdataErrorCode]
 * (ARC-19, CS-14) so that a service renders them with [Problems.renderIngestion] and writes no code of its own.
 * The title is the row's `condition`, the owner the row's `owner`; the status is the 422 that INV-Q-1841 ruled for
 * ING-006 and `intakeAggregationBatch` declares, the 409 of ING-002 (INV-REQ-434 item 5) and the 422 of ING-022.
 * Exactly these three entries exist: no other ING code is added, their statuses are not ruled. The test of this
 * enum compares the code, title and owner with the pinned snapshot.
 */
enum class IngestionErrorCode(
    val code: String,
    val httpStatus: Int,
    val title: String,
    val owner: Owner,
) {
    AGGREGATE_TYPE_NOT_PERMITTED(
        "ING-006",
        HttpStatusCodes.UNPROCESSABLE,
        "aggregateType not POS, STI or PCP, or contradicting direction (C-3; extended 25 Sep 2026)",
        Owner.INTEGRATION,
    ),
    SOURCE_REVISION_LOWER(
        "ING-002",
        HttpStatusCodes.CONFLICT,
        "sourceRevision lower than recorded",
        Owner.INTEGRATION,
    ),
    AGGREGATE_NUMBER_NOT_BUILDABLE(
        "ING-022",
        HttpStatusCodes.UNPROCESSABLE,
        "Aggregate number cannot be built: the number would exceed 64 characters, or the entity or business unit has no registered short code of 1 to 10 characters A-Z 0-9 in the structure; nothing is stored (HTTP 422; INV-REQ-436, INV-REQ-850 b7, INV-Q-11264)",
        Owner.INTEGRATION,
    ),
    ;

    /** The catalogue `owner` text of the row (error-codes.yaml). */
    enum class Owner(
        val text: String,
    ) {
        INTEGRATION("Integration"),
    }
}
