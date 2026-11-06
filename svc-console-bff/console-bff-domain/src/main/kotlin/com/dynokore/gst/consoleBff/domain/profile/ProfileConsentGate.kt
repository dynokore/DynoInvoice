package com.dynokore.gst.consoleBff.domain.profile

/** Who asks to apply a proposed operating profile. */
enum class ApplyOrigin { AGENT_DIRECT, SIGNATORY_CONSENT }

/** Why an application is refused. */
enum class RefusalReason { DIRECT_APPLICATION, NO_PROPOSAL, NO_CONSENT }

/** A profile the agent proposed in its consent request. */
data class ProposedProfile(
    val profileRef: String,
    val profileVersion: Int,
) {
    init {
        require(profileRef.isNotBlank()) { "profileRef must not be blank" }
        require(profileVersion >= 1) { "profileVersion must be at least 1" }
    }
}

/** One variation-point value of a proposal; the caller marks the client-entity ones. */
data class ProposedValue(
    val variationPoint: String,
    val clientEntity: Boolean,
) {
    init {
        require(variationPoint.isNotBlank()) { "variationPoint must not be blank" }
    }
}

sealed interface ProfileApplication {
    data class Apply(
        val profile: ProposedProfile,
        val values: List<ProposedValue>,
    ) : ProfileApplication

    data class Refused(
        val reason: RefusalReason,
    ) : ProfileApplication
}

/**
 * Decides whether a proposed profile's client-entity values may be applied. Fails closed.
 * [consentAllowed] is the per-request `consent.grant` decision taken by the caller
 * (false when the decision is not allow or is unavailable).
 */
fun decideProfileApplication(
    origin: ApplyOrigin,
    proposal: ProposedProfile?,
    values: List<ProposedValue>,
    consentAllowed: Boolean,
): ProfileApplication {
    val points = values.map { it.variationPoint }
    require(points.toSet().size == points.size) { "duplicate variationPoint" }
    return when {
        origin == ApplyOrigin.AGENT_DIRECT -> ProfileApplication.Refused(RefusalReason.DIRECT_APPLICATION)
        proposal == null -> ProfileApplication.Refused(RefusalReason.NO_PROPOSAL)
        !consentAllowed -> ProfileApplication.Refused(RefusalReason.NO_CONSENT)
        else -> ProfileApplication.Apply(proposal, values.filter { it.clientEntity })
    }
}
