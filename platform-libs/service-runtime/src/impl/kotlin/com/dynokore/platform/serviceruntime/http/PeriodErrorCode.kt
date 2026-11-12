package com.dynokore.platform.serviceruntime.http

/**
 * The period refusal PER-001 with its catalogue code (platform-contracts `catalogue/errors/error-codes.yaml`),
 * mapped in one place beside [IngestionErrorCode] (ARC-19, CS-14; RFC 9457) so that a service renders it with
 * [Problems.renderPeriod] and writes no envelope of its own. The contract of `confirmFiling` in the mode BLOCKING
 * (VP-14) refuses the call with 422 and PER-001 (platform-contracts `submission/openapi.yaml`); coordinator ruling
 * of round z, 2026-11-12 (tooling/wave1/crun/chains/req137-pending-rulings.txt), INV-T-20923.
 * The title is the row's `condition`, severity and disposition the row's, the owner the row's `owner`.
 * Exactly one entry exists, PERIOD_NOT_READY; no other PER code is added (a later PER code is its own task).
 */
enum class PeriodErrorCode(
    val code: String,
    val httpStatus: Int,
    val title: String,
    val severity: String,
    val disposition: String,
    val owner: Owner,
) {
    PERIOD_NOT_READY(
        "PER-001",
        HttpStatusCodes.UNPROCESSABLE,
        "Period contains unsubmitted in-scope documents at readiness check",
        "BLOCK",
        "OVERRIDABLE",
        Owner.TAX_OPERATIONS,
    ),
    ;

    /** The catalogue `owner` text of the row (error-codes.yaml). */
    enum class Owner(
        val text: String,
    ) {
        TAX_OPERATIONS("Tax operations"),
    }
}
