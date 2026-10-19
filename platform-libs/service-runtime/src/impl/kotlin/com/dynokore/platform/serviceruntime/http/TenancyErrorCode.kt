package com.dynokore.platform.serviceruntime.http

/**
 * The business-conflict refusals of the tenancy operations with their TNC catalogue codes (platform-contracts
 * `catalogue/errors/error-codes.yaml` TNC-001..TNC-003; coordinator ruling INV-Q-7553 A), mapped in one place beside
 * [PolicyErrorCode] (ARC-19, CS-14) so that a service renders a TNC refusal with [Problems.renderTenancy] and writes
 * no code of its own. The status is the "HTTP nnn" of the catalogue condition, the title the text of the condition
 * before its first ": ", the owner the row's `owner`; the test of this enum compares all three, and the code set,
 * with the pinned snapshot.
 */
enum class TenancyErrorCode(
    val code: String,
    val httpStatus: Int,
    val title: String,
    val owner: Owner,
) {
    TENANT_NOT_CREATED("TNC-001", HttpStatusCodes.CONFLICT, "Tenant not created", Owner.PLATFORM),
    SHORT_CODE_ALREADY_USED("TNC-002", HttpStatusCodes.CONFLICT, "Short code already used", Owner.TENANT_ADMIN),
    SHORT_CODE_FIXED("TNC-003", HttpStatusCodes.CONFLICT, "Short code fixed", Owner.TENANT_ADMIN),
    ;

    /** The catalogue `owner` text of the row (error-codes.yaml). */
    enum class Owner(
        val text: String,
    ) {
        PLATFORM("Platform"),
        TENANT_ADMIN("tenant.admin"),
    }
}
