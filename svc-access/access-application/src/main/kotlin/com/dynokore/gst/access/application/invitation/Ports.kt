package com.dynokore.gst.access.application.invitation

import com.dynokore.gst.access.domain.grant.TenantRef
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

    fun insert(invitation: NewInvitation)

    /** Whether the bound tenant has a `tenant.admin` grant of status ACTIVE or PENDING, whatever scope, source or validity. */
    fun holdsLiveTenantAdminGrant(): Boolean
}
