package com.dynokore.platform.serviceruntime.http

/**
 * The masterdata refusal MDM-005 with its catalogue code (platform-contracts `catalogue/errors/error-codes.yaml`;
 * coordinator ruling INV-Q-7886 A), mapped in one place beside [PolicyErrorCode] and [TenancyErrorCode] (ARC-19, CS-14)
 * so that a service renders it with [Problems.renderMasterdata] and writes no code of its own. The title is the
 * row's `condition`, the owner the row's `owner`; the status is the 422 that `resolveCounterparty` declares for the
 * code. The test of this enum compares the code, title and owner with the pinned snapshot.
 */
enum class MasterdataErrorCode(
    val code: String,
    val httpStatus: Int,
    val title: String,
    val owner: Owner,
) {
    IDENTITY_TRIPLE_NOT_CONFIGURED(
        "MDM-005",
        HttpStatusCodes.UNPROCESSABLE,
        "Entity identity triple not configured for the constitution",
        Owner.ONBOARDING,
    ),
    ;

    /** The catalogue `owner` text of the row (error-codes.yaml). */
    enum class Owner(
        val text: String,
    ) {
        ONBOARDING("Onboarding"),
    }
}
