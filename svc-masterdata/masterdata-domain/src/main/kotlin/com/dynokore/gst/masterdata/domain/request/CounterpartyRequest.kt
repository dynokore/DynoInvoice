package com.dynokore.gst.masterdata.domain.request

import com.dynokore.gst.masterdata.domain.counterparty.PartyRef
import java.time.Duration
import java.time.Instant

/**
 * VP-30, how counterparties are asked for their details (INV-REQ-1291). Only [SIGNED_LINK] and [BOTH] send a signed
 * request link. A stored option this service does not know is never guessed: [parse] answers null for it.
 */
enum class SendMethod(
    val sendsLink: Boolean,
) {
    SIGNED_LINK(true),
    CSV_ONLY(false),
    BOTH(true),
    OFF(false),
    ;

    companion object {
        /** The option named exactly [key]; null for any other text (no case folding, no trimming). */
        fun parse(key: String?): SendMethod? = entries.firstOrNull { it.name == key }
    }
}

/** A counterparty request masterdata-svc owns (INV-REQ-901 b1); its id is the SignedLinkRequested subject. */
data class CounterpartyRequest(
    val requestId: String,
    val tenant: String,
    val entity: String,
    val party: PartyRef,
    val contactEmail: String,
    val contactMobile: String?,
    val status: RequestStatus,
    /** The instant of the latest SignedLinkRequested of this request. */
    val lastRequestedAt: Instant,
    /** The expiry the latest SignedLinkRequested asked for. */
    val expiresAt: Instant,
) {
    init {
        require(requestId.isNotBlank()) { "a request id is not blank" }
    }
}

/** The contract's request statuses (`CounterpartyRequest.status`). */
enum class RequestStatus { SENT, ANSWERED }

/**
 * What access-svc told this service about one link (SignedLinkIssued, SignedLinkInvalidated). The two events arrive
 * at least once and in any order, so every field is optional until its event is applied and applying is idempotent.
 */
data class LinkBinding(
    val linkRef: String,
    val tenant: String?,
    val requestId: String?,
    val expiry: Instant?,
    val invalidatedAt: Instant?,
) {
    init {
        require(linkRef.isNotBlank()) { "a link reference is not blank" }
    }

    /** SignedLinkIssued: sets the issue facts; a repeat leaves the binding equal. */
    fun issued(
        tenant: String,
        requestId: String,
        expiry: Instant,
    ): LinkBinding = copy(tenant = tenant, requestId = requestId, expiry = expiry)

    /** SignedLinkInvalidated: the earliest invalidation instant is kept, so a repeat leaves the binding equal. */
    fun invalidated(at: Instant): LinkBinding = copy(invalidatedAt = invalidatedAt?.let { minOf(it, at) } ?: at)

    companion object {
        /** A binding for which no event has been applied yet. */
        fun empty(linkRef: String) = LinkBinding(linkRef, null, null, null, null)
    }
}

/** The verified end-user token of a link session: `sub` is `link:` followed by the linkRef, plus the token's tenant. */
data class LinkPrincipal(
    val subject: String,
    val tenant: String,
) {
    /** The linkRef the subject names; null when the subject is not `link:` followed by a non-blank reference. */
    fun linkRef(): String? = subject.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.takeIf { it.isNotBlank() }

    companion object {
        const val PREFIX = "link:"
    }
}

/**
 * INV-REQ-901 b3, INV-AC-2667/2672/2673/2683: whether the link evidence lets a call act on [requestId] at [now]. Every
 * check fails closed; nothing here says which one failed, so a refusal reveals nothing.
 */
fun acceptsLink(
    principal: LinkPrincipal?,
    binding: LinkBinding?,
    requestId: String,
    now: Instant,
): Boolean {
    val linkRef = principal?.linkRef()
    val expiry = binding?.expiry
    return principal != null &&
        linkRef != null &&
        binding != null &&
        expiry != null &&
        binding.linkRef == linkRef &&
        binding.invalidatedAt == null &&
        now.isBefore(expiry) &&
        binding.requestId == requestId &&
        binding.tenant == principal.tenant
}

/** Why a send is refused for its link validity (INV-AC-18554); one result, so nothing says which bound failed. */
object LinkValidity {
    /** The effective validity: the tenant value when present, else [defaultDays]; null when outside 1..[maxDays]. */
    fun effective(
        defaultDays: Long,
        maxDays: Long,
        tenantDays: Long?,
    ): Duration? {
        val days = tenantDays ?: defaultDays
        return if (days in 1..maxDays) Duration.ofDays(days) else null
    }
}
