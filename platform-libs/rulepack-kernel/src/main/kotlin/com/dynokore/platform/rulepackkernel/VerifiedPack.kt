package com.dynokore.platform.rulepackkernel

import com.dynokore.platform.tenancykernel.TenantId

/**
 * A pack that [PackVerifier] accepted, together with the signed artefact it came from and the
 * label of the trust anchor that verified it (INV-Q-8206): what a registry that persists must
 * store (the four signed members and `trusted_anchor` of `PackRegistryMigration`'s table).
 *
 * The constructor is private (CS-07): the only way to build one is [of], whose first parameter is a
 * [PackVerificationOutcome.Verified], so a [PackVerificationOutcome.Rejected] cannot produce one.
 *
 * Not a `data class` (a private constructor would still leak through `copy`): [equals] and
 * [hashCode] are written out, [PackArtefact] compares its bytes by content.
 */
class VerifiedPack private constructor(
    val version: PackVersion,
    val artefact: PackArtefact,
    val trustedAnchorLabel: String,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is VerifiedPack &&
                    version == other.version &&
                    artefact == other.artefact &&
                    trustedAnchorLabel == other.trustedAnchorLabel
            )

    override fun hashCode(): Int = arrayOf(version, artefact, trustedAnchorLabel).contentHashCode()

    override fun toString(): String =
        "VerifiedPack(version=$version, artefact=$artefact, trustedAnchorLabel=$trustedAnchorLabel)"

    companion object {
        /** The only factory: [tenant] is `null` for a platform-wide pack. */
        @JvmStatic
        fun of(
            outcome: PackVerificationOutcome.Verified,
            artefact: PackArtefact,
            tenant: TenantId? = null,
        ): VerifiedPack = VerifiedPack(PackVersion(outcome.manifest, tenant), artefact, outcome.trustedAnchorLabel)
    }
}
