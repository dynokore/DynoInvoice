package com.dynokore.gst.masterdata.application.request

import com.dynokore.gst.masterdata.domain.counterparty.PartyRef
import com.dynokore.gst.masterdata.domain.request.CounterpartyRequest
import com.dynokore.gst.masterdata.domain.request.LinkBinding
import java.time.Instant

/** The counterparty requests of the bound tenant (TPL-11); every call runs inside the caller's transaction. */
interface CounterpartyRequests {
    fun find(requestId: String): CounterpartyRequest?

    /** The open (SENT) request of [party], null when none; at most one is open per counterparty. */
    fun openFor(party: PartyRef): CounterpartyRequest?

    fun save(request: CounterpartyRequest)

    /** A new request id, unique within the tenant. */
    fun nextId(): String
}

/** What access-svc told this service about links; read by linkRef and by request. */
interface LinkBindings {
    fun find(linkRef: String): LinkBinding?

    fun forRequest(requestId: String): List<LinkBinding>

    fun save(binding: LinkBinding)
}

/** VP-30 as the tenant has it now (tenant scope); the raw option text, so an unknown option is seen, not hidden. */
fun interface SendMethodPolicy {
    fun option(): String
}

/** The link validity limits of the installation, in days; layer 2 fills them from configuration (never code). */
interface LinkValidityLimits {
    val defaultDays: Long
    val maxDays: Long
}

/** The tenant's own link validity in days; null when it has set none. */
fun interface TenantLinkValidity {
    fun days(): Long?
}

/** The payload of SignedLinkRequested (platform-contracts `signed-link-requested-payload`). */
data class SignedLinkRequest(
    val purpose: String,
    val subjectReference: String,
    val email: String,
    val mobileNumber: String?,
    val expiry: Instant,
)

/** Publishes SignedLinkRequested in the transaction of the command (TPL-05, CS-17). */
fun interface SignedLinkRequestedPublisher {
    fun publish(
        entity: String,
        request: SignedLinkRequest,
    )
}

/** The audit entry of an accepted submission: the acting principal (`link:<linkRef>`) and the request, never a role. */
fun interface SubmissionAudit {
    fun accepted(
        principal: String,
        requestId: String,
    )
}

/** The two stores a request command works on, in one transaction. */
data class RequestStores(
    val requests: CounterpartyRequests,
    val bindings: LinkBindings,
)

/** What a send reads to decide whether and for how long: VP-30, the installation limits and the tenant value. */
data class SendPolicies(
    val method: SendMethodPolicy,
    val limits: LinkValidityLimits,
    val tenantValidity: TenantLinkValidity,
)
