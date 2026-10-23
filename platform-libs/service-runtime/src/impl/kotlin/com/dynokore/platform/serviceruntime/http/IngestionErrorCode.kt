package com.dynokore.platform.serviceruntime.http

/**
 * The ingestion refusal ING-006 with its catalogue code (platform-contracts `catalogue/errors/error-codes.yaml`;
 * coordinator rulings INV-Q-8795 A and INV-Q-8797 A), mapped in one place beside [PolicyErrorCode],
 * [TenancyErrorCode] and [MasterdataErrorCode] (ARC-19, CS-14) so that a service renders it with
 * [Problems.renderIngestion] and writes no code of its own. The title is the row's `condition`, the owner the row's
 * `owner`; the status is the 422 that INV-Q-1841 ruled for ING-006 and `intakeAggregationBatch` declares. No other
 * ING code is added: their statuses are not ruled. The test of this enum compares the code, title and owner with the
 * pinned snapshot.
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
    ;

    /** The catalogue `owner` text of the row (error-codes.yaml). */
    enum class Owner(
        val text: String,
    ) {
        INTEGRATION("Integration"),
    }
}
