package com.dynokore.platform.serviceruntime.http

/**
 * The masterdata refusals MDM-005 and MDM-007 with its catalogue code (platform-contracts `catalogue/errors/error-codes.yaml`;
 * coordinator ruling INV-Q-7886 A), mapped in one place beside [PolicyErrorCode] and [TenancyErrorCode] (ARC-19, CS-14)
 * so that a service renders it with [Problems.renderMasterdata] and writes no code of its own. The title is the
 * row's `condition`, the owner the row's `owner`; the status is the one the operation declares for the code (422 for MDM-005
 * on `resolveCounterparty`, 409 for MDM-007 on `confirmRegisterSuggestion`; INV-Q-16979 A); the severity is the row's. The test of this enum compares the code, title, owner and severity with the pinned snapshot.
 */
enum class MasterdataErrorCode(
    val code: String,
    val httpStatus: Int,
    val title: String,
    val owner: Owner,
    val severity: String,
) {
    IDENTITY_TRIPLE_NOT_CONFIGURED(
        "MDM-005",
        HttpStatusCodes.UNPROCESSABLE,
        "Entity identity triple not configured for the constitution",
        Owner.ONBOARDING,
        "BLOCK",
    ),
    COUNTERPARTY_GSTN_CONFLICT(
        "MDM-007",
        HttpStatusCodes.CONFLICT,
        "Counterparty GSTN conflicts with the IRAS register",
        Owner.FINANCE,
        "WARN",
    ),
    ;

    /** The catalogue `owner` text of the row (error-codes.yaml). */
    enum class Owner(
        val text: String,
    ) {
        ONBOARDING("Onboarding"),
        FINANCE("Finance"),
    }
}
