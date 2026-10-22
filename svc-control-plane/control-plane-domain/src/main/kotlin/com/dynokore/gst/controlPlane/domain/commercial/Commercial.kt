package com.dynokore.gst.controlPlane.domain.commercial

private val REF = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

/** The reference of a tenant, customer or installation: opaque, 1..64 characters (the contract's maxLength 64). */
@JvmInline
value class CommercialRef(
    val value: String,
) {
    init {
        require(value.matches(REF)) { "a reference is 1..64 letters, digits, '.', '-' or '_'" }
    }

    override fun toString(): String = value
}

/** The version of a plan: 1..16 characters (`ManagePlanRequest.planVersion`). */
@JvmInline
value class PlanVersion(
    val value: String,
) {
    init {
        require(value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,15}"))) {
            "a plan version is 1..16 letters, digits, '.', '-' or '_'"
        }
    }

    override fun toString(): String = value
}

/** The advisory limits of a plan (INV-ADR-272): a limit never blocks a request, so zero is a valid limit. */
data class Entitlements(
    val maxEntities: Int,
    val maxDocumentsPerMonth: Int,
) {
    init {
        require(maxEntities >= 0 && maxDocumentsPerMonth >= 0) { "an entitlement limit is not negative" }
    }
}

/** One stored version of the plan of a tenant (INV-REQ-906 b1). Versions are stored, never overwritten. */
data class Plan(
    val tenantRef: CommercialRef,
    val version: PlanVersion,
    val entitlements: Entitlements,
)

enum class QualificationOutcome { QUALIFIED, NOT_QUALIFIED }

/** The qualification outcome recorded for a customer's installation (INV-REQ-906 b1). */
data class Qualification(
    val customerRef: CommercialRef,
    val installationRef: CommercialRef,
    val outcome: QualificationOutcome,
)
