package com.dynokore.gst.access.domain.revocation

import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class RevocationKind { PRINCIPAL, SUBJECT, TOKEN }

/**
 * One entry of the revocation list the gateway refreshes (INV-API-4001, INV-Q-3973 A). It lives until [expiresAt], the
 * revocation time plus the longest access-token lifetime (API-23); after that it is no longer returned.
 */
data class RevocationEntry(
    val entryId: UUID,
    val version: Long,
    val kind: RevocationKind,
    val principalRef: String?,
    val subject: String?,
    val tokenId: String?,
    val revokedAt: Instant,
) {
    init {
        require(version >= 1) { "an entry's version is at least 1" }
        val named = listOfNotNull(principalRef, subject, tokenId)
        require(named.size == 1 && named.single().isNotBlank()) {
            "an entry names exactly one of principalRef, subject, tokenId"
        }
        require(
            when (kind) {
                RevocationKind.PRINCIPAL -> principalRef != null
                RevocationKind.SUBJECT -> subject != null
                RevocationKind.TOKEN -> tokenId != null
            },
        ) { "an entry of kind $kind names its own field" }
    }

    val expiresAt: Instant get() = revokedAt.plus(ACCESS_TOKEN_LIFETIME)

    fun isLiveAt(at: Instant): Boolean = at.isBefore(expiresAt)

    companion object {
        /** The longest access-token lifetime, 15 minutes (API-23). */
        val ACCESS_TOKEN_LIFETIME: Duration = Duration.ofMinutes(15)
    }
}

/** A token verification key (INV-API-4001 sibling: `getVerificationKeys`): DER SubjectPublicKeyInfo as standard base64. */
data class VerificationKey(
    val kid: String,
    val alg: String,
    val publicKey: String,
    val validFrom: Instant,
    val validUntil: Instant,
) {
    init {
        require(kid.isNotBlank() && kid.length <= 32) { "kid is required, at most 32 characters" }
        require(alg.isNotBlank() && alg.length <= 16) { "alg is required, at most 16 characters" }
        require(publicKey.isNotBlank() && publicKey.length <= 8192) { "publicKey is required, at most 8192 characters" }
        require(validUntil.isAfter(validFrom)) { "a key must be valid for a positive time" }
    }

    fun isUsableAt(at: Instant): Boolean = !at.isBefore(validFrom) && at.isBefore(validUntil)
}
