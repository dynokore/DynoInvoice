package com.dynokore.gst.access.application.invitation

import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Instant
import java.util.UUID

/** The identity a pending first administrator grant is given to, until the invited person accepts (INV-Q-14264 A). */
object InvitationIdentity {
    const val PREFIX = "invitation:"

    /** Whether [text] is in the reserved space; exact, case-sensitive prefix. */
    fun isReserved(text: String): Boolean = text.startsWith(PREFIX)
}

/** The version of the code key a new provisioning uses; configuration with no default. */
class InvitationSettings(
    val currentKeyVersion: String,
) {
    init {
        require(KEY_VERSION.matches(currentKeyVersion)) { "the invitation code key version must look like v1" }
    }

    companion object {
        val KEY_VERSION = Regex("^v[0-9]{1,3}$")
    }
}

/** The secret of the code key of a version is missing or unreadable. */
class InvitationKeyUnavailable(
    cause: Throwable? = null,
) : RuntimeException("the invitation code key is unavailable", cause)

/** The one-time code of an invitation (INV-Q-14261 A). */
interface InvitationCodes {
    /** The code of [invitationRef] under the key [keyVersion]; throws [InvitationKeyUnavailable]. */
    fun codeOf(
        tenant: TenantRef,
        invitationRef: String,
        keyVersion: String,
    ): String

    /** The digest that is stored in place of the code. */
    fun digestOf(code: String): String
}

fun interface InvitationRefs {
    fun next(): String

    companion object {
        const val MAX_LENGTH = 48
    }
}

/** A stored invitation as a repeat needs it. */
data class StoredInvitation(
    val invitationRef: String,
    val grantRef: String,
    val codeKeyVersion: String,
    val requestFingerprint: String,
    /** True once the invitation was reissued: the row then holds a newer invitation and this one is void. */
    val reissued: Boolean = false,
)

/** What a reissue writes on the invitation row: the new reference, digest, key version, expiry and the reissue key. */
data class InvitationReplacement(
    val invitationRef: String,
    val codeDigest: String,
    val codeKeyVersion: String,
    val expiresAt: Instant,
    val reissueKey: UUID,
    val reissueFingerprint: String,
)

/** The invitation row a reissue key was stored on (the latest reissue of the grant). */
data class StoredReissue(
    val invitationRef: String,
    val grantRef: String,
    val codeKeyVersion: String,
    val reissueFingerprint: String,
)

/** A stored invitation as an acceptance needs it; [codeDigest] is null once the invitation was accepted. */
data class InvitationRecord(
    val grantRef: String,
    val expiresAt: Instant,
    val codeDigest: String?,
    val acceptKey: UUID? = null,
    val acceptFingerprint: String? = null,
)

/** The tenant of an invitation, found from its reference alone (the token of an acceptance carries no tenant). */
fun interface InvitationTenantLookup {
    /** The tenant of [invitationRef], or null when none has that reference; runs under the platform binding. */
    fun tenantOf(invitationRef: String): TenantId?
}

/** One `tenant.admin` grant of the bound tenant that is ACTIVE or PENDING, with its invitation row when it has one. */
data class LiveAdminGrant(
    val grantRef: String,
    val status: String,
    val invitationRef: String?,
    val invitationExpiresAt: Instant?,
)

data class NewInvitation(
    val invitationRef: String,
    val grantRef: String,
    val invitedEmail: String,
    val expiresAt: Instant,
    val codeDigest: String,
    val codeKeyVersion: String,
    val provisioningKey: UUID,
    val requestFingerprint: String,
)

/** Persistence of the invitation rows of the bound tenant (row-level security restricts them). */
interface FirstAdministratorInvitations {
    /** Serialises the provisioning calls of the bound tenant until the transaction ends. */
    fun lockTenant()

    fun findByKey(key: UUID): StoredInvitation?

    /** The invitation of the bound tenant whose latest reissue used this key. */
    fun findByReissueKey(key: UUID): StoredReissue?

    fun insert(invitation: NewInvitation)

    /** The invitation of the bound tenant with this reference. */
    fun findByRef(invitationRef: String): InvitationRecord?

    /** Clears the code digest so the code cannot be used again and stores the key and fingerprint of the acceptance. */
    fun markAccepted(
        invitationRef: String,
        acceptKey: UUID,
        acceptFingerprint: String,
    )

    /** Whether the bound tenant has a `tenant.admin` grant that is ACTIVE or PENDING, whatever scope or source. */
    fun holdsLiveTenantAdminGrant(): Boolean

    /** Every `tenant.admin` grant of the bound tenant that is ACTIVE or PENDING, whatever scope or source. */
    fun liveTenantAdminGrants(): List<LiveAdminGrant>

    /**
     * Replaces the reference, code digest, key version and expiry of the invitation of [grantRef] (INV-Q-8232 A); the
     * old reference and digest are gone from the row. Throws when the grant has no invitation row.
     */
    fun reissue(
        grantRef: String,
        replacement: InvitationReplacement,
    )
}
