package com.dynokore.gst.access.application.link

import com.dynokore.gst.access.domain.link.SignedLink
import java.time.Instant

/**
 * The role record a link stands for (INV-Q-9222 A, INV-Q-8972 A): the link principal `link:<linkRef>` holds
 * `client.signatory` at CLIENT_ENTITY scope on the client entity of the link until the link expires. Appended to the
 * outbox in the transaction of the caller; no service calls access-svc at request time.
 */
interface ClientSignatoryGrants {
    /** Publishes the ACTIVE record of [link], valid from [issuedAt] to the expiry of the link. */
    fun issued(
        link: SignedLink,
        issuedAt: Instant,
    )

    /**
     * Publishes the REMOVED record of [link] at [at], at a higher authority version. The only caller in this build is
     * the invalidation by the fifth wrong passcode (INV-AC-2765); no revoke path exists.
     */
    fun removed(
        link: SignedLink,
        at: Instant,
    )

    companion object {
        /**
         * Which links get a record: only a link issued for the client signatory of an agent consent, which carries a
         * consent and a client entity. A counterparty link issued on SignedLinkRequested carries neither.
         */
        fun applies(link: SignedLink): Boolean = link.client != null && link.consent != null
    }
}

/**
 * Publishes the REMOVED role record of a client-signatory link in the same call that appends `SignedLinkInvalidated`,
 * so the transaction of the verify path carries both (INV-AC-2765). The verify use case itself is unchanged.
 */
class RemovingClientSignatoryOnInvalidation(
    private val delegate: LinkInvalidations,
    private val grants: ClientSignatoryGrants,
) : LinkInvalidations {
    override fun invalidated(
        link: SignedLink,
        reason: LinkInvalidationReason,
    ) {
        delegate.invalidated(link, reason)
        if (ClientSignatoryGrants.applies(link)) {
            grants.removed(link, checkNotNull(link.invalidatedAt) { "an invalidated link has its time" })
        }
    }
}
