package com.dynokore.gst.access.application.link

import com.dynokore.gst.access.application.alert.AccessAlerts
import com.dynokore.gst.access.domain.link.LinkEntry
import com.dynokore.gst.access.domain.link.LinkRef
import com.dynokore.gst.access.domain.link.SignedLink
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Clock

/**
 * Persistence of signed links. Every read runs under the bound tenant: the database policy restricts the rows. Nothing
 * here caches.
 */
interface SignedLinks {
    fun find(ref: LinkRef): SignedLink?

    /** Locks the link row until the transaction ends; two entries of the passcode never interleave. */
    fun lock(ref: LinkRef): SignedLink?

    /** The link stored for the delivery [eventId] of SignedLinkRequested, or null. */
    fun findBySourceEvent(eventId: String): SignedLink?

    /** Stores [link]; the acting user ID is the bound actor, never a role. */
    fun insert(link: SignedLink)

    /** Replaces [before] by [after]; throws the platform's StaleAggregate when [before] is no longer current. */
    fun update(
        before: SignedLink,
        after: SignedLink,
    )
}

/** The tenant of a link, found from its reference alone under the platform binding; null for an unknown reference. */
fun interface LinkTenants {
    fun tenantOf(ref: LinkRef): TenantId?
}

/** The digest kept in place of a passcode, and the check of an entry against it. */
interface PasscodeDigests {
    fun digestOf(
        ref: LinkRef,
        passcode: String,
    ): String

    /** Whether [passcode] is the one [digest] was made from; constant time. */
    fun matches(
        ref: LinkRef,
        passcode: String,
        digest: String,
    ): Boolean
}

/** VP-75 as the installation sets it (INV-REQ-2758); read live at every call. */
data class LinkPasscodeSetting(
    /** Wrong entries that invalidate a link: 5 by default, fewer when VP-75 sets it, never more. */
    val maxWrongEntries: Int,
    /** SMS only: no link goes to a recipient without a mobile number, and no e-mail replaces an SMS. */
    val smsOnly: Boolean,
) {
    init {
        require(maxWrongEntries in 1..SignedLink.MAX_WRONG_ENTRIES) { "the wrong-entry limit is 1 to five" }
    }

    companion object {
        val DEFAULT = LinkPasscodeSetting(SignedLink.MAX_WRONG_ENTRIES, smsOnly = false)
    }
}

fun interface LinkPasscodeSettings {
    fun current(): LinkPasscodeSetting
}

sealed interface VerifyLinkOutcome {
    /** The passcode matched: the verified session of the link is open. */
    data class Verified(
        val ref: LinkRef,
        val wrongEntries: Int,
    ) : VerifyLinkOutcome

    data class Wrong(
        val ref: LinkRef,
        val wrongEntries: Int,
    ) : VerifyLinkOutcome

    /** The entry that reached the limit invalidated the link. */
    data class Invalidated(
        val ref: LinkRef,
        val wrongEntries: Int,
    ) : VerifyLinkOutcome

    /** The link is unknown or expired: one answer for both (401), so a caller learns nothing about the reference. */
    data object Refused : VerifyLinkOutcome

    /** The link was invalidated or revoked before this entry (410 ACC-004). */
    data object Gone : VerifyLinkOutcome
}

/**
 * `verifySignatoryLink` (INV-REQ-899, INV-REQ-2758, INV-AC-2765): checks an entry of the one-time passcode against the
 * link. The link row is locked first, so concurrent entries are counted one by one. A wrong entry is stored and
 * answered; the entry that reaches the VP-75 limit invalidates the link and raises ACC-004 to the sender in the same
 * transaction; after that even the correct passcode is refused. A correct entry opens the verified session (the
 * `verified_at` of the record); no token is issued.
 */
class VerifySignatoryLinkUseCase(
    private val tx: TransactionRunner,
    private val links: SignedLinks,
    private val digests: PasscodeDigests,
    private val settings: LinkPasscodeSettings,
    private val alerts: AccessAlerts,
    private val clock: Clock,
) {
    fun handle(
        ref: LinkRef,
        passcode: String,
    ): VerifyLinkOutcome =
        tx.inTransaction {
            val link = links.lock(ref) ?: return@inTransaction VerifyLinkOutcome.Refused
            val now = clock.instant()
            val limit = settings.current().maxWrongEntries
            val entry = link.enter(digests.matches(ref, passcode, link.passcodeDigest), limit, now)
            when (entry) {
                is LinkEntry.Verified -> {
                    if (entry.link != link) links.update(link, entry.link)
                    VerifyLinkOutcome.Verified(ref, entry.link.wrongEntries)
                }

                is LinkEntry.Wrong -> {
                    links.update(link, entry.link)
                    VerifyLinkOutcome.Wrong(ref, entry.link.wrongEntries)
                }

                is LinkEntry.Invalidated -> {
                    links.update(link, entry.link)
                    raiseToSender(entry.link)
                    VerifyLinkOutcome.Invalidated(ref, entry.link.wrongEntries)
                }

                LinkEntry.Expired -> {
                    VerifyLinkOutcome.Refused
                }

                LinkEntry.Unusable -> {
                    VerifyLinkOutcome.Gone
                }
            }
        }

    /** ACC-004 is filed under the alert entity of the link: its client entity, or the envelope entity of the event. */
    private fun raiseToSender(link: SignedLink) {
        alerts.raise(ACC_LINK_INVALIDATED, "entity:${link.alertEntity.value}")
    }

    companion object {
        const val ACC_LINK_INVALIDATED = "ACC-004"

        /** The actor of every write a signed-link session makes (the form of INV-Q-9222 A); never a role. */
        fun actorOf(ref: LinkRef): String = "link:${ref.value}"
    }
}
