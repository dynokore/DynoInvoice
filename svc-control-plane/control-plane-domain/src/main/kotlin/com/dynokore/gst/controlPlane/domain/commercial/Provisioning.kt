package com.dynokore.gst.controlPlane.domain.commercial

import java.time.Instant

/**
 * The key of an entry on the platform's region list (INV-REQ-1306): 1..64 characters, compared by equality, so `sg`
 * is not `SG`. Whether it is on the list is the region list's answer, not this type's.
 */
@JvmInline
value class RegionKey(
    val value: String,
) {
    init {
        require(value.length in 1..MAX_LENGTH) { "a region key is 1..$MAX_LENGTH characters" }
    }

    override fun toString(): String = value

    companion object {
        const val MAX_LENGTH = 64
    }
}

/** The plan reference a provisioning request carries: opaque, passed on unchanged, 1..64 characters (INV-Q-8691). */
@JvmInline
value class PlanRef(
    val value: String,
) {
    init {
        require(value.length in 1..MAX_LENGTH) { "a plan reference is 1..$MAX_LENGTH characters" }
    }

    override fun toString(): String = value

    companion object {
        const val MAX_LENGTH = 64
    }
}

enum class ProvisioningStatus { PENDING, CREATED }

/**
 * The recorded request to provision a SaaS tenant (INV-REQ-1306, INV-API-3495): the tenant reference minted when it
 * was recorded, the elected region, and the signed expiring first invitation. The region is fixed for the life of the
 * record: the type has no way to change it.
 */
data class TenantProvisioning(
    val contractReference: CommercialRef,
    val tenantRef: CommercialRef,
    val legalName: String,
    val planRef: PlanRef,
    val firstAdministratorEmail: String,
    val dataRegion: RegionKey,
    val status: ProvisioningStatus,
    val invitationRef: String,
    val invitationExpiresAt: Instant,
)

/** A licence issued for an installation term (INV-REQ-906 b1): [signedFile] is the exact signed document. */
data class Licence(
    val licenceRef: CommercialRef,
    val installationRef: CommercialRef,
    val term: String,
    val issuedAt: Instant,
    val signedFile: ByteArray,
) {
    init {
        require(term.length in 1..MAX_TERM_LENGTH) { "a term is 1..$MAX_TERM_LENGTH characters" }
    }

    override fun equals(other: Any?): Boolean =
        other is Licence &&
            licenceRef == other.licenceRef &&
            installationRef == other.installationRef &&
            term == other.term &&
            issuedAt == other.issuedAt &&
            signedFile.contentEquals(other.signedFile)

    override fun hashCode(): Int = licenceRef.hashCode()

    companion object {
        const val MAX_TERM_LENGTH = 32
    }
}
