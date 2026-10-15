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
    UNKNOWN_VARIATION_POINT("POL-001", 404, "Variation point unknown", Owner.PLATFORM, null),
    VARIATION_POINT_RETIRED("POL-002", 422, "Variation point retired", Owner.PLATFORM, null),
    SCOPE_NOT_ALLOWED("POL-003", 422, "Scope level not allowed", Owner.SETTING_ROLE, PolicyRefusal.SCOPE_NOT_ALLOWED),
    ROLE_NOT_ALLOWED("POL-004", 403, "Role not allowed", Owner.SETTING_ROLE, PolicyRefusal.ROLE_NOT_ALLOWED),
    REASON_REQUIRED("POL-005", 400, "Reason missing", Owner.SETTING_ROLE, PolicyRefusal.REASON_REQUIRED),
    PLANNED("POL-006", 422, "Variation point planned", Owner.PLATFORM, PolicyRefusal.PLANNED),
    NOT_SUPPORTED("POL-007", 422, "Not supported", Owner.PLATFORM, PolicyRefusal.NOT_SUPPORTED),
    LOOSER_THAN_BROADER(
        "POL-008",
        422,
        "Looser than a broader scope",
        Owner.SETTING_ROLE,
        PolicyRefusal.LOOSER_THAN_BROADER,
    ),
    BEYOND_INSTALLATION_BOUND(
        "POL-009",
        422,
        "Beyond the installation bound",
        Owner.SETTING_ROLE,
        PolicyRefusal.BEYOND_INSTALLATION_BOUND,
    ),
    BOUND_UNAVAILABLE("POL-010", 422, "Bound unavailable", Owner.PLATFORM, PolicyRefusal.BOUND_UNAVAILABLE),
    UNKNOWN_OPTION("POL-011", 422, "Unknown option", Owner.SETTING_ROLE, PolicyRefusal.UNKNOWN_OPTION),
    SELECTION_INVALID("POL-012", 400, "Selection invalid", Owner.SETTING_ROLE, PolicyRefusal.SELECTION_INVALID),
    OUT_OF_BOUNDS("POL-013", 422, "Out of bounds", Owner.SETTING_ROLE, PolicyRefusal.OUT_OF_BOUNDS),
    CONSTRAINT_REFUSED("POL-014", 422, "Constraint refused", Owner.SETTING_ROLE, PolicyRefusal.CONSTRAINT_REFUSED),
    LOOSER_NOT_OFFERED(
        "POL-015",
        422,
        "Looser option not offered",
        Owner.SETTING_ROLE,
        PolicyRefusal.LOOSER_NOT_OFFERED,
    ),
    LOOSER_AT_INSTALLATION(
        "POL-016",
        422,
        "Looser option at installation scope",
        Owner.SETTING_ROLE,
        PolicyRefusal.LOOSER_AT_INSTALLATION,
    ),
    NO_ACCOUNTABLE_ROLE("POL-017", 422, "No accountable role", Owner.PLATFORM, PolicyRefusal.NO_ACCOUNTABLE_ROLE),
    RELAXATION_REQUIRED(
        "POL-018",
        409,
        "Relaxation required",
        Owner.ACCOUNTABLE_ROLE,
        PolicyRefusal.RELAXATION_REQUIRED,
    ),
    ACKNOWLEDGER_NOT_ALLOWED(
        "POL-019",
        403,
        "Acknowledger not allowed",
        Owner.ACCOUNTABLE_ROLE,
        PolicyRefusal.ACKNOWLEDGER_NOT_ALLOWED,
    ),
    ACKNOWLEDGER_DUPLICATE(
        "POL-020",
        409,
        "Acknowledger duplicate",
        Owner.ACCOUNTABLE_ROLE,
        PolicyRefusal.ACKNOWLEDGER_DUPLICATE,
    ),
    NOT_LOOSER("POL-021", 422, "Not looser", Owner.SETTING_ROLE, PolicyRefusal.NOT_LOOSER),
    BACKDATING_REFUSED("POL-022", 422, "Back-dating refused", Owner.SETTING_ROLE, PolicyRefusal.BACKDATING_REFUSED),
    PROFILE_INCOMPLETE("POL-023", 400, "Profile incomplete", Owner.SETTING_ROLE, PolicyRefusal.PROFILE_INCOMPLETE),
    PROFILE_AT_INSTALLATION(
        "POL-024",
        422,
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
        /** The code of a refusal of config-resolver's writer. */
        fun of(refusal: PolicyRefusal): PolicyErrorCode =
            when (refusal) {
                PolicyRefusal.SCOPE_NOT_ALLOWED -> SCOPE_NOT_ALLOWED
                PolicyRefusal.ROLE_NOT_ALLOWED -> ROLE_NOT_ALLOWED
                PolicyRefusal.REASON_REQUIRED -> REASON_REQUIRED
                PolicyRefusal.PLANNED -> PLANNED
                PolicyRefusal.NOT_SUPPORTED -> NOT_SUPPORTED
                PolicyRefusal.LOOSER_THAN_BROADER -> LOOSER_THAN_BROADER
                PolicyRefusal.BEYOND_INSTALLATION_BOUND -> BEYOND_INSTALLATION_BOUND
                PolicyRefusal.BOUND_UNAVAILABLE -> BOUND_UNAVAILABLE
                PolicyRefusal.UNKNOWN_OPTION -> UNKNOWN_OPTION
                PolicyRefusal.SELECTION_INVALID -> SELECTION_INVALID
                PolicyRefusal.OUT_OF_BOUNDS -> OUT_OF_BOUNDS
                PolicyRefusal.CONSTRAINT_REFUSED -> CONSTRAINT_REFUSED
                PolicyRefusal.LOOSER_NOT_OFFERED -> LOOSER_NOT_OFFERED
                PolicyRefusal.LOOSER_AT_INSTALLATION -> LOOSER_AT_INSTALLATION
                PolicyRefusal.NO_ACCOUNTABLE_ROLE -> NO_ACCOUNTABLE_ROLE
                PolicyRefusal.RELAXATION_REQUIRED -> RELAXATION_REQUIRED
                PolicyRefusal.ACKNOWLEDGER_NOT_ALLOWED -> ACKNOWLEDGER_NOT_ALLOWED
                PolicyRefusal.ACKNOWLEDGER_DUPLICATE -> ACKNOWLEDGER_DUPLICATE
                PolicyRefusal.NOT_LOOSER -> NOT_LOOSER
                PolicyRefusal.BACKDATING_REFUSED -> BACKDATING_REFUSED
                PolicyRefusal.PROFILE_INCOMPLETE -> PROFILE_INCOMPLETE
                PolicyRefusal.PROFILE_AT_INSTALLATION -> PROFILE_AT_INSTALLATION
            }
    }
}
