package com.dynokore.platform.serviceruntime.http

/**
 * The submission refusals SUB-008 and SUB-025 with their catalogue codes (invoice-docs
 * `model/architecture/error-codes.yaml`), mapped in one place beside [PeriodErrorCode] (ARC-19, CS-14; RFC 9457) so
 * that a service renders them with [Problems.renderSubmission] and writes no envelope of its own. The catalogue rows
 * have no status member, so the status is carried here: SUB-025 names HTTP 409 in its condition, and SUB-008 is ruled
 * 409 (RFC 9110 section 15.5.10). Coordinator ruling (delegated, owner 2026-10-14) at the reviewer stop of
 * INV-T-20607, option A; INV-T-21366. The title is the row's `condition`, severity and disposition the row's, the
 * owner the row's `owner`.
 * Exactly two entries exist, IN_FLIGHT_BREACH and VERSION_NOT_REPLAYABLE; no other SUB code is added (a later SUB
 * code is its own task).
 */
enum class SubmissionErrorCode(
    val code: String,
    val httpStatus: Int,
    val title: String,
    val severity: String,
    val disposition: String,
    val owner: Owner,
) {
    IN_FLIGHT_BREACH(
        "SUB-008",
        HttpStatusCodes.CONFLICT,
        "Two in-flight versions of one Doc UUID (FR-C.2 breach)",
        "BLOCK",
        "TERMINAL",
        Owner.PLATFORM,
    ),
    VERSION_NOT_REPLAYABLE(
        "SUB-025",
        HttpStatusCodes.CONFLICT,
        "Replay refused: the version is in a state other than DEAD_LETTERED or QUEUED, so it is not replayable; " +
            "its state is unchanged and nothing is enqueued (HTTP 409; INV-REQ-575)",
        "BLOCK",
        "TERMINAL",
        Owner.PLATFORM,
    ),
    ;

    /** The catalogue `owner` text of the row (error-codes.yaml). */
    enum class Owner(
        val text: String,
    ) {
        PLATFORM("Platform"),
    }
}
