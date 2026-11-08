package com.dynokore.gst.masterdata.application.request

import com.dynokore.gst.masterdata.application.counterparty.CounterpartyRepository
import com.dynokore.gst.masterdata.application.counterparty.MasterDataResolvedPublisher
import com.dynokore.gst.masterdata.domain.counterparty.CounterpartyChange
import com.dynokore.gst.masterdata.domain.counterparty.CounterpartyVersion
import com.dynokore.gst.masterdata.domain.counterparty.IdentifierInput
import com.dynokore.gst.masterdata.domain.counterparty.PostalAddress
import com.dynokore.gst.masterdata.domain.counterparty.WriteOrigin
import com.dynokore.gst.masterdata.domain.counterparty.after
import com.dynokore.gst.masterdata.domain.request.LinkBinding
import com.dynokore.gst.masterdata.domain.request.LinkPrincipal
import com.dynokore.gst.masterdata.domain.request.RequestStatus
import com.dynokore.gst.masterdata.domain.request.acceptsLink
import com.dynokore.platform.partyregistry.IdentifierScheme
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Clock
import java.time.Instant

/** What the counterparty confirmed (INV-REQ-902). */
data class CounterpartyDetails(
    val uen: String?,
    val legalName: String?,
    val address: PostalAddress?,
)

sealed interface SubmitOutcome {
    data class Accepted(
        val version: CounterpartyVersion,
    ) : SubmitOutcome

    /** One result for every link refusal (INV-AC-18230): it does not say which check failed. */
    data object Refused : SubmitOutcome

    /** The link is accepted but a required value is absent; nothing is stored. */
    data object Incomplete : SubmitOutcome
}

/**
 * INV-REQ-902, INV-REQ-901 b3, INV-AC-18230. Applies SignedLinkIssued and SignedLinkInvalidated idempotently, in any
 * order ([applyIssued], [applyInvalidated]), and stores a counterparty's confirmed values as COUNTERPARTY-provided only
 * when the link evidence of the verified token matches the request of the call. The evidence reference is the request
 * and link (`link:<linkRef>/<requestId>`), so it is never empty. Values are not validated as a precondition (FR-1.5).
 */
class SubmitCounterpartyDetails(
    private val tx: TransactionRunner,
    private val stores: RequestStores,
    private val parties: CounterpartyRepository,
    private val clock: Clock,
    private val publisher: MasterDataResolvedPublisher,
    private val audit: SubmissionAudit,
) {
    fun applyIssued(
        linkRef: String,
        tenant: String,
        requestId: String,
        expiry: Instant,
    ) = tx.inTransaction {
        stores.bindings.save(
            (stores.bindings.find(linkRef) ?: LinkBinding.empty(linkRef)).issued(tenant, requestId, expiry),
        )
    }

    fun applyInvalidated(
        linkRef: String,
        invalidatedAt: Instant,
    ) = tx.inTransaction {
        stores.bindings.save((stores.bindings.find(linkRef) ?: LinkBinding.empty(linkRef)).invalidated(invalidatedAt))
    }

    fun handle(
        principal: LinkPrincipal?,
        requestId: String,
        details: CounterpartyDetails,
    ): SubmitOutcome =
        tx.inTransaction {
            val now = clock.instant()
            val linkRef = principal?.linkRef()
            if (!acceptsLink(principal, linkRef?.let(stores.bindings::find), requestId, now)) {
                return@inTransaction SubmitOutcome.Refused
            }
            val verified = principal ?: return@inTransaction SubmitOutcome.Refused
            val request = stores.requests.find(requestId)
            if (request == null || request.tenant != verified.tenant) return@inTransaction SubmitOutcome.Refused
            val uen = details.uen?.trim().orEmpty()
            val name = details.legalName?.trim().orEmpty()
            val address = details.address
            if (uen.isEmpty() || name.isEmpty() || address?.line1.isNullOrBlank()) {
                return@inTransaction SubmitOutcome.Incomplete
            }
            val evidence = "${verified.subject}/$requestId"
            val change =
                CounterpartyChange(listOf(IdentifierInput(IdentifierScheme.UEN, uen)), name, address, null, null)
            val content =
                parties.current(request.party)?.content.after(change, evidence, WriteOrigin.COUNTERPARTY_LINK, now)
            val version = parties.append(request.party, content, now)
            publisher.publish(request.entity, version)
            stores.requests.save(request.copy(status = RequestStatus.ANSWERED))
            audit.accepted(verified.subject, requestId)
            SubmitOutcome.Accepted(version)
        }
}
