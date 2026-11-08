package com.dynokore.gst.masterdata.application.request

import com.dynokore.gst.masterdata.domain.counterparty.PartyRef
import com.dynokore.gst.masterdata.domain.request.CounterpartyRequest
import com.dynokore.gst.masterdata.domain.request.LinkValidity
import com.dynokore.gst.masterdata.domain.request.RequestStatus
import com.dynokore.gst.masterdata.domain.request.SendMethod
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Clock

/** One send: the counterparty, the entity it is asked for, and the contact the link goes to (INV-REQ-901). */
data class SendCommand(
    val entity: String,
    val party: PartyRef,
    val contactEmail: String?,
    val contactMobile: String?,
)

/** The outcome of a send; a refusal stores and emits nothing. */
sealed interface SendOutcome {
    /** The open request: new, re-issued or the existing live one (then nothing was emitted). */
    data class Sent(
        val request: CounterpartyRequest,
        val emitted: Boolean,
    ) : SendOutcome

    /** `contactEmail` absent or blank: a validation refusal. */
    data object InvalidContact : SendOutcome

    /** VP-30 is CSV_ONLY or OFF (409 at layer 2). */
    data object MethodNotLink : SendOutcome

    /** VP-30 holds an option this service does not know; it is never guessed. */
    data object UnknownMethod : SendOutcome

    /** The effective link validity is below 1 day or above the maximum; fails closed. */
    data object InvalidValidity : SendOutcome
}

/**
 * INV-REQ-901 b1, INV-REQ-1291, INV-AC-2277, INV-AC-9600, INV-AC-18231, INV-AC-18554. Publishes SignedLinkRequested and
 * never calls access-svc (DR-11). A second send while the link is live returns the open request and emits nothing; a
 * send after expiry or invalidation emits again for the same request id (re-issue).
 */
class SendCounterpartyRequest(
    private val tx: TransactionRunner,
    private val requests: CounterpartyRequests,
    private val bindings: LinkBindings,
    private val method: SendMethodPolicy,
    private val limits: LinkValidityLimits,
    private val tenantValidity: TenantLinkValidity,
    private val tenant: () -> String,
    private val clock: Clock,
    private val publisher: SignedLinkRequestedPublisher,
) {
    fun handle(command: SendCommand): SendOutcome =
        tx.inTransaction {
            val email = command.contactEmail?.trim().orEmpty()
            if (email.isEmpty()) return@inTransaction SendOutcome.InvalidContact
            val option = SendMethod.parse(method.option()) ?: return@inTransaction SendOutcome.UnknownMethod
            if (!option.sendsLink) return@inTransaction SendOutcome.MethodNotLink
            val validity =
                LinkValidity.effective(limits.defaultDays, limits.maxDays, tenantValidity.days())
                    ?: return@inTransaction SendOutcome.InvalidValidity
            val now = clock.instant()
            val open = requests.openFor(command.party)
            if (open != null && live(open, now)) return@inTransaction SendOutcome.Sent(open, false)
            val request =
                CounterpartyRequest(
                    requestId = open?.requestId ?: requests.nextId(),
                    tenant = tenant(),
                    entity = command.entity,
                    party = command.party,
                    contactEmail = email,
                    contactMobile = command.contactMobile?.trim()?.takeIf { it.isNotEmpty() },
                    status = RequestStatus.SENT,
                    lastRequestedAt = now,
                    expiresAt = now.plus(validity),
                )
            requests.save(request)
            publisher.publish(
                request.entity,
                SignedLinkRequest(PURPOSE, request.requestId, request.contactEmail, request.contactMobile, request.expiresAt),
            )
            SendOutcome.Sent(request, true)
        }

    /**
     * The link of [request] is live while its requested expiry is ahead and no link of the request was invalidated
     * after the latest SignedLinkRequested.
     */
    private fun live(
        request: CounterpartyRequest,
        now: java.time.Instant,
    ): Boolean =
        now.isBefore(request.expiresAt) &&
            bindings.forRequest(request.requestId).none {
                it.invalidatedAt?.isAfter(request.lastRequestedAt) == true
            }

    companion object {
        /** The `purpose` of the SignedLinkRequested this use case emits. */
        const val PURPOSE = "COUNTERPARTY_DETAILS"
    }
}
