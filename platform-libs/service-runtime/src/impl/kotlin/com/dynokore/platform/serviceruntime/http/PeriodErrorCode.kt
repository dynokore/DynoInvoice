package com.dynokore.platform.serviceruntime.http

/**
 * The period refusals PER-001, PER-017 and PER-018 with its catalogue code (platform-contracts `catalogue/errors/error-codes.yaml`),
 * mapped in one place beside [IngestionErrorCode] (ARC-19, CS-14; RFC 9457) so that a service renders it with
 * [Problems.renderPeriod] and writes no envelope of its own. The contract of `confirmFiling` in the mode BLOCKING
 * (VP-14) refuses the call with 422 and PER-001 (platform-contracts `submission/openapi.yaml`); coordinator ruling
 * of round z, 2026-11-12 (tooling/wave1/crun/chains/req137-pending-rulings.txt), INV-T-20923.
 * The title is the row's `condition`, severity and disposition the row's, the owner the row's `owner`.
 * Exactly three entries exist, PERIOD_NOT_READY, ATTESTATION_SEPARATION_OF_DUTIES (PER-017, 403) and
 * ATTESTATION_UNEXPLAINED_RESIDUAL (PER-018, 409), the last two by coordinator ruling INV-T-25147 (breakdown
 * tooling/gap24459-gap.md, INV-T-24459 option A); no other PER code is added (a later PER code is its own task).
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
    ATTESTATION_SEPARATION_OF_DUTIES(
        "PER-017",
        HttpStatusCodes.FORBIDDEN,
        "Attestation refused by separation of duties: the approver is the preparer of the run and no single-attestation election of the entity is in force; nothing is attested (HTTP 403; INV-REQ-947, INV-REQ-994, INV-Q-9704)",
        "BLOCK",
        "MANUAL",
        Owner.TAX_OPERATIONS,
    ),
    ATTESTATION_UNEXPLAINED_RESIDUAL(
        "PER-018",
        HttpStatusCodes.CONFLICT,
        "Attestation refused: an unexplained residual remains in the run, so a variance is neither classified nor annotated; nothing is attested (HTTP 409; INV-REQ-947)",
        "BLOCK",
        "MANUAL",
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
