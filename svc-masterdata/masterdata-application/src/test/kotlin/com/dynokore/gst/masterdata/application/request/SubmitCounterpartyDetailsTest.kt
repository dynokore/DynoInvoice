package com.dynokore.gst.masterdata.application.request

import com.dynokore.gst.masterdata.domain.counterparty.PostalAddress
import com.dynokore.gst.masterdata.domain.request.CounterpartyRequest
import com.dynokore.gst.masterdata.domain.request.LinkBinding
import com.dynokore.gst.masterdata.domain.request.LinkPrincipal
import com.dynokore.gst.masterdata.domain.request.RequestStatus
import com.dynokore.platform.partyregistry.IdentifierScheme
import com.dynokore.platform.partyregistry.ProvidedBy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

class SubmitCounterpartyDetailsTest {
    private val clock = MutableClock()
    private val requests = FakeRequests()
    private val bindings = FakeBindings()
    private val parties = FakeParties()
    private val out = Recorder()
    private val use = SubmitCounterpartyDetails(DirectTx, RequestStores(requests, bindings), parties, clock, out, out)
    private val details =
        CounterpartyDetails("201954321B", "Acme Trading Pte Ltd", PostalAddress("1 Raffles Place", null, "048616"))

    private fun request(
        id: String,
        tenant: String = TENANT,
    ): CounterpartyRequest =
        CounterpartyRequest(
            id,
            tenant,
            "ENT-1",
            PARTY,
            "ap@example.com.sg",
            null,
            RequestStatus.SENT,
            T0,
            T0.plus(Duration.ofDays(7)),
        ).also { requests.save(it) }

    private fun issue(
        link: String,
        id: String,
        tenant: String = TENANT,
        expiry: java.time.Instant = T0.plus(Duration.ofDays(7)),
    ) = use.applyIssued(link, tenant, id, expiry)

    private fun principal(
        link: String,
        tenant: String = TENANT,
    ) = LinkPrincipal("link:$link", tenant)

    /** Everything a refused call must leave equal. */
    private fun state() = Triple(parties.versions.toList(), requests.all.toMap(), out.resolved.size to out.audits.size)

    private fun refused(
        principal: LinkPrincipal?,
        requestId: String,
    ) {
        val before = state()
        assertEquals(SubmitOutcome.Refused, use.handle(principal, requestId, details))
        assertEquals(before, state())
    }

    @Test
    fun `INV-AC-18230 an accepted submission is stored COUNTERPARTY-provided and emits one MasterDataResolved`() {
        request("REQ-1")
        issue("L1", "REQ-1")
        val outcome = use.handle(principal("L1"), "REQ-1", details) as SubmitOutcome.Accepted
        val content = outcome.version.content
        assertEquals(ProvidedBy.COUNTERPARTY, content.providedBy)
        val uen = content.identifiers.single { it.scheme == IdentifierScheme.UEN }
        assertEquals("201954321B", uen.value)
        assertEquals(ProvidedBy.COUNTERPARTY, uen.provenance.providedBy)
        assertTrue(
            uen.provenance.evidenceRef.value
                .isNotEmpty(),
        )
        assertEquals("Acme Trading Pte Ltd", content.legalName)
        assertEquals("1 Raffles Place", content.address?.line1)
        assertEquals(1, out.resolved.size)
        assertEquals(listOf("link:L1" to "REQ-1"), out.audits)
        assertEquals(RequestStatus.ANSWERED, requests.find("REQ-1")?.status)
    }

    @Test
    fun `INV-AC-9600 provenance is COUNTERPARTY and the invalid link leaves the record equal`() {
        request("REQ-1")
        issue("L1", "REQ-1")
        refused(principal("L1x"), "REQ-1")
        val ok = use.handle(principal("L1"), "REQ-1", details) as SubmitOutcome.Accepted
        assertEquals(ProvidedBy.COUNTERPARTY, parties.current(PARTY)?.content?.providedBy)
        assertTrue(
            ok.version.content.identifiers
                .all {
                    it.provenance.evidenceRef.value
                        .isNotEmpty()
                },
        )
    }

    @Test
    fun `INV-AC-18230 each link refusal stores and emits nothing and the records stay equal`() {
        request("REQ-1")
        issue("L1", "REQ-1")
        refused(null, "REQ-1") // absent principal
        refused(LinkPrincipal("sub-of-a-user", TENANT), "REQ-1") // not a link subject
        refused(LinkPrincipal("link:", TENANT), "REQ-1") // empty linkRef
        refused(principal("UNKNOWN"), "REQ-1") // unknown linkRef
        refused(principal("L1", tenant = "22222222-2222-2222-2222-222222222222"), "REQ-1") // foreign tenant
        refused(principal("L1"), "REQ-OTHER") // requestId differing from the bound one
        issue("L2", "REQ-1", expiry = T0.plusSeconds(5))
        clock.now = T0.plusSeconds(5)
        refused(principal("L2"), "REQ-1") // expired: the instant is not before the expiry
        use.applyInvalidated("L1", T0)
        refused(principal("L1"), "REQ-1") // invalidated
    }

    @Test
    fun `INV-AC-2667 an expired link is refused and no value is recorded`() {
        request("REQ-1")
        issue("L1", "REQ-1", expiry = T0.plusSeconds(60))
        clock.now = T0.plusSeconds(61)
        refused(principal("L1"), "REQ-1")
        assertTrue(parties.versions.isEmpty())
    }

    @Test
    fun `INV-AC-2671 a call without a verified link session records nothing`() {
        request("REQ-1")
        issue("L1", "REQ-1")
        refused(null, "REQ-1")
        assertTrue(parties.versions.isEmpty())
    }

    @Test
    fun `INV-AC-2672 an altered link reference differing in one character is refused`() {
        request("REQ-1")
        issue("LNK-771A", "REQ-1")
        listOf("LNK-771B", "lnk-771a", "LNK-771A ", "LNK-771", "LNK-771AA").forEach { refused(principal(it), "REQ-1") }
        assertTrue(parties.versions.isEmpty())
    }

    @Test
    fun `INV-AC-2673 the first link cannot act on the second request and the second request is unchanged`() {
        request("REQ-1")
        request("REQ-2")
        issue("L1", "REQ-1")
        issue("L2", "REQ-2")
        refused(principal("L1"), "REQ-2")
        assertEquals(RequestStatus.SENT, requests.find("REQ-2")?.status)
        assertInstanceOf(SubmitOutcome.Accepted::class.java, use.handle(principal("L2"), "REQ-2", details))
    }

    @Test
    fun `INV-AC-2683 an invalidated link is refused even before its issue is seen and a re-issued link is accepted`() {
        request("REQ-1")
        use.applyInvalidated("L1", T0) // invalidation before the issue
        issue("L1", "REQ-1")
        refused(principal("L1"), "REQ-1")
        issue("L2", "REQ-1")
        assertInstanceOf(SubmitOutcome.Accepted::class.java, use.handle(principal("L2"), "REQ-1", details))
    }

    @Test
    fun `INV-AC-2683 applying the binding events twice or in the other order leaves the binding equal`() {
        val expiry = T0.plus(Duration.ofDays(7))
        issue("A", "REQ-1", expiry = expiry)
        use.applyInvalidated("A", T0.plusSeconds(3))
        val once = bindings.find("A")
        issue("A", "REQ-1", expiry = expiry)
        use.applyInvalidated("A", T0.plusSeconds(3))
        use.applyInvalidated("A", T0.plusSeconds(9))
        assertEquals(once, bindings.find("A"))
        use.applyInvalidated("B", T0.plusSeconds(3))
        issue("B", "REQ-1", expiry = expiry)
        assertEquals(once?.copy(linkRef = "B"), bindings.find("B"))
        assertEquals(LinkBinding.empty("C").linkRef, "C")
    }

    @Test
    fun `INV-AC-18230 a request stored under another tenant than the token and binding is refused`() {
        request("REQ-1", tenant = "22222222-2222-2222-2222-222222222222")
        issue("L1", "REQ-1")
        refused(principal("L1"), "REQ-1")
    }

    @Test
    fun `a binding of another tenant for the same request id is refused`() {
        request("REQ-1")
        issue("L1", "REQ-1", tenant = "22222222-2222-2222-2222-222222222222")
        refused(principal("L1"), "REQ-1")
    }

    @Test
    fun `INV-AC-18230 a missing or blank required value is Incomplete and stores nothing`() {
        request("REQ-1")
        issue("L1", "REQ-1")
        val before = state()
        listOf(
            CounterpartyDetails(null, "Acme", details.address),
            CounterpartyDetails("201954321B", " ", details.address),
            CounterpartyDetails("201954321B", "Acme", null),
            CounterpartyDetails("201954321B", "Acme", PostalAddress(" ", null, null)),
            CounterpartyDetails("201954321B", "Acme", PostalAddress("", null, null)),
            CounterpartyDetails("", "Acme", details.address),
            CounterpartyDetails("  ", "Acme", details.address),
            CounterpartyDetails("201954321B", "", details.address),
        ).forEach { assertEquals(SubmitOutcome.Incomplete, use.handle(principal("L1"), "REQ-1", it)) }
        assertEquals(before, state())
    }

    @Test
    fun `INV-AC-18230 a second submit once ANSWERED is refused through the same and a second live link`() {
        request("REQ-1")
        issue("L1", "REQ-1")
        issue("L2", "REQ-1")
        use.handle(principal("L1"), "REQ-1", details) as SubmitOutcome.Accepted
        val after = state()
        val other = details.copy(legalName = "Other Name Pte Ltd")
        assertEquals(SubmitOutcome.Refused, use.handle(principal("L1"), "REQ-1", other))
        assertEquals(SubmitOutcome.Refused, use.handle(principal("L2"), "REQ-1", other))
        assertEquals(after, state())
        assertEquals(1, out.resolved.size)
    }

    @Test
    fun `INV-AC-18230 a format-free UEN is accepted and a new SENT request after an ANSWERED one is accepted`() {
        request("REQ-1")
        issue("L1", "REQ-1")
        use.handle(principal("L1"), "REQ-1", details) as SubmitOutcome.Accepted
        request("REQ-2")
        issue("L2", "REQ-2")
        assertInstanceOf(
            SubmitOutcome.Accepted::class.java,
            use.handle(principal("L2"), "REQ-2", details.copy(uen = "x-1")),
        )
        assertEquals(2, out.resolved.size)
    }

    @Test
    fun `INV-AC-1938 store part the confirmed values are stored with the request evidence`() {
        request("REQ-1")
        issue("L1", "REQ-1")
        val v = (use.handle(principal("L1"), "REQ-1", details) as SubmitOutcome.Accepted).version
        assertEquals(
            "link:L1/REQ-1",
            v.content.identifiers
                .single()
                .provenance.evidenceRef.value,
        )
    }

    @Test
    fun `INV-AC-2668 API part a verified counterparty writes only the counterparty of its own request`() {
        val other =
            com.dynokore.gst.masterdata.domain.counterparty
                .PartyRef("CTP-999")
        request("REQ-1")
        issue("L1", "REQ-1")
        use.handle(principal("L1"), "REQ-1", details)
        assertEquals(listOf(PARTY), parties.versions.map { it.partyRef }.distinct())
        assertEquals(null, parties.current(other))
    }
}
