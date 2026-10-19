package com.dynokore.platform.serviceruntime.http

import com.dynokore.platform.configresolver.PolicyRefusal

/**
 * The refusals of `setPolicy` and `acknowledgeControlRelaxation` with their POL catalogue codes (platform-contracts
 * `catalogue/errors/error-codes.yaml` POL-001..POL-024; coordinator rulings INV-Q-5172 and INV-Q-5237), mapped in one
 * place beside [ClosedErrorCode] (ARC-19, CS-14): `UnknownVariationPoint` ([UNKNOWN_VARIATION_POINT]),
 * `VariationPointRetired` ([VARIATION_POINT_RETIRED]) and every
 * [PolicyRefusal] value. The status is the "HTTP nnn" of the catalogue condition, the title the text of the
 * condition before its first ": ", the owner the row's `owner`; the test of this enum compares all three, and the
 * code set, with the pinned snapshot. [of] has no `else`: a [PolicyRefusal] member added later does not compile
 * until it is mapped here.
 */
enum class PolicyErrorCode(
    val code: String,
    val httpStatus: Int,
    val title: String,
    val owner: Owner,
    val refusal: PolicyRefusal?,
) {
    UNKNOWN_VARIATION_POINT("POL-001", HttpStatusCodes.NOT_FOUND, "Variation point unknown", Owner.PLATFORM, null),
    VARIATION_POINT_RETIRED("POL-002", HttpStatusCodes.UNPROCESSABLE, "Variation point retired", Owner.PLATFORM, null),
    SCOPE_NOT_ALLOWED(
        "POL-003",
        HttpStatusCodes.UNPROCESSABLE,
        "Scope level not allowed",
        Owner.SETTING_ROLE,
        PolicyRefusal.SCOPE_NOT_ALLOWED,
    ),
    ROLE_NOT_ALLOWED(
        "POL-004",
        HttpStatusCodes.FORBIDDEN,
        "Role not allowed",
        Owner.SETTING_ROLE,
        PolicyRefusal.ROLE_NOT_ALLOWED,
    ),
    REASON_REQUIRED(
        "POL-005",
        HttpStatusCodes.BAD_REQUEST,
        "Reason missing",
        Owner.SETTING_ROLE,
        PolicyRefusal.REASON_REQUIRED,
    ),
    PLANNED("POL-006", HttpStatusCodes.UNPROCESSABLE, "Variation point planned", Owner.PLATFORM, PolicyRefusal.PLANNED),
    NOT_SUPPORTED(
        "POL-007",
        HttpStatusCodes.UNPROCESSABLE,
        "Not supported",
        Owner.PLATFORM,
        PolicyRefusal.NOT_SUPPORTED,
    ),
    LOOSER_THAN_BROADER(
        "POL-008",
        HttpStatusCodes.UNPROCESSABLE,
        "Looser than a broader scope",
        Owner.SETTING_ROLE,
        PolicyRefusal.LOOSER_THAN_BROADER,
    ),
    BEYOND_INSTALLATION_BOUND(
        "POL-009",
        HttpStatusCodes.UNPROCESSABLE,
        "Beyond the installation bound",
        Owner.SETTING_ROLE,
        PolicyRefusal.BEYOND_INSTALLATION_BOUND,
    ),
    BOUND_UNAVAILABLE(
        "POL-010",
        HttpStatusCodes.UNPROCESSABLE,
        "Bound unavailable",
        Owner.PLATFORM,
        PolicyRefusal.BOUND_UNAVAILABLE,
    ),
    UNKNOWN_OPTION(
        "POL-011",
        HttpStatusCodes.UNPROCESSABLE,
        "Unknown option",
        Owner.SETTING_ROLE,
        PolicyRefusal.UNKNOWN_OPTION,
    ),
    SELECTION_INVALID(
        "POL-012",
        HttpStatusCodes.BAD_REQUEST,
        "Selection invalid",
        Owner.SETTING_ROLE,
        PolicyRefusal.SELECTION_INVALID,
    ),
    OUT_OF_BOUNDS(
        "POL-013",
        HttpStatusCodes.UNPROCESSABLE,
        "Out of bounds",
        Owner.SETTING_ROLE,
        PolicyRefusal.OUT_OF_BOUNDS,
    ),
    CONSTRAINT_REFUSED(
        "POL-014",
        HttpStatusCodes.UNPROCESSABLE,
        "Constraint refused",
        Owner.SETTING_ROLE,
        PolicyRefusal.CONSTRAINT_REFUSED,
    ),
    LOOSER_NOT_OFFERED(
        "POL-015",
        HttpStatusCodes.UNPROCESSABLE,
        "Looser option not offered",
        Owner.SETTING_ROLE,
        PolicyRefusal.LOOSER_NOT_OFFERED,
    ),
    LOOSER_AT_INSTALLATION(
        "POL-016",
        HttpStatusCodes.UNPROCESSABLE,
        "Looser option at installation scope",
        Owner.SETTING_ROLE,
        PolicyRefusal.LOOSER_AT_INSTALLATION,
    ),
    NO_ACCOUNTABLE_ROLE(
        "POL-017",
        HttpStatusCodes.UNPROCESSABLE,
        "No accountable role",
        Owner.PLATFORM,
        PolicyRefusal.NO_ACCOUNTABLE_ROLE,
    ),
    RELAXATION_REQUIRED(
        "POL-018",
        HttpStatusCodes.CONFLICT,
        "Relaxation required",
        Owner.ACCOUNTABLE_ROLE,
        PolicyRefusal.RELAXATION_REQUIRED,
    ),
    ACKNOWLEDGER_NOT_ALLOWED(
        "POL-019",
        HttpStatusCodes.FORBIDDEN,
        "Acknowledger not allowed",
        Owner.ACCOUNTABLE_ROLE,
        PolicyRefusal.ACKNOWLEDGER_NOT_ALLOWED,
    ),
    ACKNOWLEDGER_DUPLICATE(
        "POL-020",
        HttpStatusCodes.CONFLICT,
        "Acknowledger duplicate",
        Owner.ACCOUNTABLE_ROLE,
        PolicyRefusal.ACKNOWLEDGER_DUPLICATE,
    ),
    NOT_LOOSER("POL-021", HttpStatusCodes.UNPROCESSABLE, "Not looser", Owner.SETTING_ROLE, PolicyRefusal.NOT_LOOSER),
    BACKDATING_REFUSED(
        "POL-022",
        HttpStatusCodes.UNPROCESSABLE,
        "Back-dating refused",
        Owner.SETTING_ROLE,
        PolicyRefusal.BACKDATING_REFUSED,
    ),
    PROFILE_INCOMPLETE(
        "POL-023",
        HttpStatusCodes.BAD_REQUEST,
        "Profile incomplete",
        Owner.SETTING_ROLE,
        PolicyRefusal.PROFILE_INCOMPLETE,
    ),
    PROFILE_AT_INSTALLATION(
        "POL-024",
        HttpStatusCodes.UNPROCESSABLE,
        "Profile at installation scope",
        Owner.SETTING_ROLE,
        PolicyRefusal.PROFILE_AT_INSTALLATION,
    ),
    ;

    /** The catalogue `owner` text of the row (error-codes.yaml). */
    enum class Owner(
        val text: String,
    ) {
        PLATFORM("Platform"),
        SETTING_ROLE("The setting role of the variation point"),
        ACCOUNTABLE_ROLE("The accountable role named for the control"),
    }

    companion object {
        private val BY_REFUSAL: Map<PolicyRefusal, PolicyErrorCode> =
            mapOf(
                PolicyRefusal.SCOPE_NOT_ALLOWED to SCOPE_NOT_ALLOWED,
                PolicyRefusal.ROLE_NOT_ALLOWED to ROLE_NOT_ALLOWED,
                PolicyRefusal.REASON_REQUIRED to REASON_REQUIRED,
                PolicyRefusal.PLANNED to PLANNED,
                PolicyRefusal.NOT_SUPPORTED to NOT_SUPPORTED,
                PolicyRefusal.LOOSER_THAN_BROADER to LOOSER_THAN_BROADER,
                PolicyRefusal.BEYOND_INSTALLATION_BOUND to BEYOND_INSTALLATION_BOUND,
                PolicyRefusal.BOUND_UNAVAILABLE to BOUND_UNAVAILABLE,
                PolicyRefusal.UNKNOWN_OPTION to UNKNOWN_OPTION,
                PolicyRefusal.SELECTION_INVALID to SELECTION_INVALID,
                PolicyRefusal.OUT_OF_BOUNDS to OUT_OF_BOUNDS,
                PolicyRefusal.CONSTRAINT_REFUSED to CONSTRAINT_REFUSED,
                PolicyRefusal.LOOSER_NOT_OFFERED to LOOSER_NOT_OFFERED,
                PolicyRefusal.LOOSER_AT_INSTALLATION to LOOSER_AT_INSTALLATION,
                PolicyRefusal.NO_ACCOUNTABLE_ROLE to NO_ACCOUNTABLE_ROLE,
                PolicyRefusal.RELAXATION_REQUIRED to RELAXATION_REQUIRED,
                PolicyRefusal.ACKNOWLEDGER_NOT_ALLOWED to ACKNOWLEDGER_NOT_ALLOWED,
                PolicyRefusal.ACKNOWLEDGER_DUPLICATE to ACKNOWLEDGER_DUPLICATE,
                PolicyRefusal.NOT_LOOSER to NOT_LOOSER,
                PolicyRefusal.BACKDATING_REFUSED to BACKDATING_REFUSED,
                PolicyRefusal.PROFILE_INCOMPLETE to PROFILE_INCOMPLETE,
                PolicyRefusal.PROFILE_AT_INSTALLATION to PROFILE_AT_INSTALLATION,
            )

        init {
            check(BY_REFUSAL.keys == PolicyRefusal.entries.toSet()) { "every PolicyRefusal maps to a code" }
        }

        /** The code of a refusal of config-resolver's writer. */
        fun of(refusal: PolicyRefusal): PolicyErrorCode = BY_REFUSAL.getValue(refusal)
    }
}
