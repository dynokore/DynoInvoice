package com.dynokore.gst.masterdata.application.request

import com.dynokore.gst.masterdata.application.counterparty.CounterpartyRepository
import com.dynokore.gst.masterdata.application.counterparty.MasterDataResolvedPublisher
import com.dynokore.gst.masterdata.domain.counterparty.CounterpartyContent
import com.dynokore.gst.masterdata.domain.counterparty.CounterpartyVersion
import com.dynokore.gst.masterdata.domain.counterparty.PartyRef
import com.dynokore.gst.masterdata.domain.request.CounterpartyRequest
import com.dynokore.gst.masterdata.domain.request.LinkBinding
import com.dynokore.gst.masterdata.domain.request.RequestStatus
import com.dynokore.platform.partyregistry.IdentifierScheme
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

internal val T0: Instant = Instant.parse("2026-11-08T00:00:00Z")
internal const val TENANT = "11111111-1111-1111-1111-111111111111"
internal val PARTY = PartyRef("CTP-118")

internal class MutableClock(
    var now: Instant = T0,
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = now
}

internal object DirectTx : TransactionRunner {
    override fun <T> inTransaction(block: () -> T): T = block()
}

internal class FakeRequests : CounterpartyRequests {
    val all = linkedMapOf<String, CounterpartyRequest>()
    private var seq = 0

    override fun find(requestId: String): CounterpartyRequest? = all[requestId]

    override fun openFor(party: PartyRef) =
        all.values.lastOrNull { it.party == party && it.status == RequestStatus.SENT }

    override fun save(request: CounterpartyRequest) {
        all[request.requestId] = request
    }

    override fun nextId() = "REQ-${++seq}"
}

internal class FakeBindings : LinkBindings {
    val all = linkedMapOf<String, LinkBinding>()

    override fun find(linkRef: String) = all[linkRef]

    override fun forRequest(requestId: String) = all.values.filter { it.requestId == requestId }

    override fun save(binding: LinkBinding) {
        all[binding.linkRef] = binding
    }
}

internal class FakeParties : CounterpartyRepository {
    val versions = mutableListOf<CounterpartyVersion>()

    override fun findByIdentifier(
        scheme: IdentifierScheme,
        value: String,
    ) = null

    override fun current(party: PartyRef) = versions.lastOrNull { it.partyRef == party }

    override fun append(
        party: PartyRef,
        content: CounterpartyContent,
        recordedAt: Instant,
        importId: String?,
    ): CounterpartyVersion {
        val next = (current(party)?.version ?: 0) + 1
        return CounterpartyVersion(party, next, recordedAt, content, importId).also { versions += it }
    }

    override fun allCurrent() = versions.groupBy { it.partyRef }.values.map { it.last() }

    override fun history(
        party: PartyRef,
        afterVersion: Long,
        limit: Int,
    ) = versions.filter { it.partyRef == party && it.version > afterVersion }.take(limit)
}

internal class Recorder :
    SignedLinkRequestedPublisher,
    MasterDataResolvedPublisher,
    SubmissionAudit {
    val links = mutableListOf<SignedLinkRequest>()
    val resolved = mutableListOf<CounterpartyVersion>()
    val resolvedDocUuids = mutableListOf<List<UUID>>()
    val audits = mutableListOf<Pair<String, String>>()

    override fun publish(
        entity: String,
        request: SignedLinkRequest,
    ) {
        links += request
    }

    override fun publish(
        entity: String,
        version: CounterpartyVersion,
        docUuids: List<UUID>,
    ) {
        resolved += version
        resolvedDocUuids += docUuids
    }

    override fun accepted(
        principal: String,
        requestId: String,
    ) {
        audits += principal to requestId
    }
}

internal class Limits(
    override val defaultDays: Long = 7,
    override val maxDays: Long = 30,
) : LinkValidityLimits
