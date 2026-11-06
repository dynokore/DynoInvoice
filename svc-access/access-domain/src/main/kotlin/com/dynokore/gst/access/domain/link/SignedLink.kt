package com.dynokore.gst.access.domain.link

import com.dynokore.gst.access.domain.consent.ConsentRef
import com.dynokore.gst.access.domain.grant.EntityRef
import java.time.Instant

/** The reference of a signed link: opaque, 1 to 64 characters. */
@JvmInline
value class LinkRef(
    val value: String,
) {
    init {
        require(value.isNotBlank() && value.length <= MAX_LENGTH) {
            "a link reference is required, 1 to $MAX_LENGTH characters"
        }
    }

    companion object {
        const val MAX_LENGTH = 64
    }
}

enum class LinkStatus { ACTIVE, INVALIDATED, REVOKED }

/** How a link came to exist: by the `issueSignatoryLink` call, or on the event SignedLinkRequested (DR-11). */
enum class LinkSource { CALL, EVENT }

/** Where the link's messages go. The mobile number is absent when none is on file. */
data class LinkRecipient(
    val email: String,
    val mobile: String?,
)

/**
 * A signed, expiring link. A link issued by the call is scoped to one consent and its client entity ([consent] and
 * [client]); a link issued on the event carries neither. The passcode is held only as its [passcodeDigest]. The
 * verified session of the link is [verifiedAt] (INV-REQ-899); it ends no later than [expiresAt] and at invalidation
 * or revocation.
 */
data class SignedLink(
    val ref: LinkRef,
    val purpose: String,
    val client: EntityRef?,
    val consent: ConsentRef?,
    /**
     * The entity ACC-004 is filed under: the client entity of a call-issued link, the envelope entity of an
     * event-issued link (ruling INV-Q-17207 A).
     */
    val alertEntity: EntityRef,
    val subject: String?,
    val recipient: LinkRecipient,
    val expiresAt: Instant,
    val passcodeDigest: String,
    val status: LinkStatus,
    val wrongEntries: Int,
    val verifiedAt: Instant?,
    val invalidatedAt: Instant?,
    val source: LinkSource,
    /** The id of the event that triggered the link (its origin); present exactly for [LinkSource.EVENT]. */
    val sourceEventId: String?,
    val version: Long,
) {
    init {
        require((consent == null) == (client == null)) { "the consent and the client entity are both given or neither" }
        require(
            client == null || client == alertEntity,
        ) { "a call-issued link files its alert under the client entity" }
        require((source == LinkSource.EVENT) == (sourceEventId != null)) { "only an event link has a delivery id" }
        require(wrongEntries in 0..MAX_WRONG_ENTRIES) { "wrong entries are 0 to $MAX_WRONG_ENTRIES" }
    }

    /** Whether the link is still usable at all: not invalidated, not revoked (expiry is looked at separately). */
    val usable: Boolean get() = status == LinkStatus.ACTIVE

    /** Whether the verified session of the link is open at [now] (INV-REQ-899, INV-AC-926). */
    fun sessionOpen(now: Instant): Boolean =
        status == LinkStatus.ACTIVE && verifiedAt != null && now.isBefore(expiresAt)

    /**
     * One entry of the passcode ([correct] says whether it matched). The limit is the VP-75 value and never exceeds
     * five (INV-REQ-2758); the entry that reaches it invalidates the link, and from then on even the correct passcode
     * is refused. An invalidated or revoked link is answered before its expiry is looked at.
     */
    fun enter(
        correct: Boolean,
        limit: Int,
        now: Instant,
    ): LinkEntry {
        require(limit in 1..MAX_WRONG_ENTRIES) { "the wrong-entry limit is 1 to $MAX_WRONG_ENTRIES" }
        return when {
            status != LinkStatus.ACTIVE -> {
                LinkEntry.Unusable
            }

            !now.isBefore(expiresAt) -> {
                LinkEntry.Expired
            }

            correct -> {
                LinkEntry.Verified(copy(verifiedAt = verifiedAt ?: now))
            }

            wrongEntries + 1 >= limit -> {
                LinkEntry.Invalidated(
                    copy(wrongEntries = wrongEntries + 1, status = LinkStatus.INVALIDATED, invalidatedAt = now),
                )
            }

            else -> {
                LinkEntry.Wrong(copy(wrongEntries = wrongEntries + 1))
            }
        }
    }

    companion object {
        /** The limit of wrong entries never exceeds five; VP-75 may set fewer. */
        const val MAX_WRONG_ENTRIES = 5
    }
}

/** What one entry of a passcode did to a link. */
sealed interface LinkEntry {
    data class Verified(
        val link: SignedLink,
    ) : LinkEntry

    data class Wrong(
        val link: SignedLink,
    ) : LinkEntry

    /** The entry that reached the limit: the link is now invalidated. */
    data class Invalidated(
        val link: SignedLink,
    ) : LinkEntry

    /** The link expired: refused, and nothing changes. */
    data object Expired : LinkEntry

    /** The link was invalidated or revoked before this entry: refused, and nothing changes (ACC-004). */
    data object Unusable : LinkEntry
}
