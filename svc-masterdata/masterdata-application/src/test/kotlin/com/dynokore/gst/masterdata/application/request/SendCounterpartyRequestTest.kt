package com.dynokore.gst.masterdata.application.request

import com.dynokore.gst.masterdata.domain.request.RequestStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

class SendCounterpartyRequestTest {
    private val clock = MutableClock()
    private val requests = FakeRequests()
    private val bindings = FakeBindings()
    private val out = Recorder()

    private fun send(
        option: String = "SIGNED_LINK",
        limits: Limits = Limits(),
        tenantDays: Long? = null,
        email: String? = "ap@example.com.sg",
        mobile: String? = null,
    ): SendOutcome =
        SendCounterpartyRequest(DirectTx, requests, bindings, { option }, limits, { tenantDays }, { TENANT }, clock, out)
            .handle(SendCommand("ENT-1", PARTY, email, mobile))

    @Test
    fun `INV-AC-2277 the default request route sends a signed link`() {
        val outcome = send(mobile = "+6591234567") as SendOutcome.Sent
        assertEquals(1, out.links.size)
        val link = out.links.single()
        assertEquals(outcome.request.requestId, link.subjectReference)
        assertEquals("ap@example.com.sg", link.email)
        assertEquals("+6591234567", link.mobileNumber)
        assertEquals(RequestStatus.SENT, outcome.request.status)
    }

    @Test
    fun `INV-AC-9600 a second send returns the open request and publishes one SignedLinkRequested in all`() {
        val first = send() as SendOutcome.Sent
        clock.now = T0.plusSeconds(60)
        val second = send() as SendOutcome.Sent
        assertEquals(first.request.requestId, second.request.requestId)
        assertEquals(false, second.emitted)
        assertEquals(1, out.links.size)
    }

    @Test
    fun `INV-AC-2683 after expiry the send re-issues the same request id with a new SignedLinkRequested`() {
        val first = send() as SendOutcome.Sent
        clock.now = T0.plus(Duration.ofDays(7))
        val again = send() as SendOutcome.Sent
        assertEquals(first.request.requestId, again.request.requestId)
        assertEquals(2, out.links.size)
        assertEquals(clock.now.plus(Duration.ofDays(7)), out.links.last().expiry)
    }

    @Test
    fun `INV-AC-2683 after an invalidation the send re-issues once and a further send returns that open request`() {
        val first = send() as SendOutcome.Sent
        clock.now = T0.plusSeconds(10)
        bindings.save(
            com.dynokore.gst.masterdata.domain.request.LinkBinding
                .empty("L1")
                .issued(TENANT, first.request.requestId, T0.plusSeconds(100))
                .invalidated(clock.now),
        )
        val again = send() as SendOutcome.Sent
        assertEquals(first.request.requestId, again.request.requestId)
        assertEquals(2, out.links.size)
        clock.now = T0.plusSeconds(20)
        assertEquals(false, (send() as SendOutcome.Sent).emitted)
        assertEquals(2, out.links.size)
    }

    @Test
    fun `INV-AC-18231 SIGNED_LINK and BOTH send`() {
        listOf("SIGNED_LINK", "BOTH").forEach {
            val fresh = SendCounterpartyRequestTest()
            val outcome = fresh.send(option = it)
            assertInstanceOf(SendOutcome.Sent::class.java, outcome, it)
            assertEquals(1, fresh.out.links.size, it)
            assertEquals(1, fresh.requests.all.size, it)
        }
    }

    @Test
    fun `INV-AC-18231 CSV_ONLY OFF and an unknown option refuse and store and emit nothing`() {
        mapOf(
            "CSV_ONLY" to SendOutcome.MethodNotLink,
            "OFF" to SendOutcome.MethodNotLink,
            "signed_link" to SendOutcome.UnknownMethod,
            "" to SendOutcome.UnknownMethod,
            "SIGNED_LINK " to SendOutcome.UnknownMethod,
            "BOTH,OFF" to SendOutcome.UnknownMethod,
        ).forEach { (option, expected) ->
            val fresh = SendCounterpartyRequestTest()
            assertEquals(expected, fresh.send(option = option), option)
            assertTrue(fresh.requests.all.isEmpty(), option)
            assertTrue(fresh.out.links.isEmpty(), option)
        }
    }

    @Test
    fun `an absent or blank contact e-mail is refused and nothing is stored or published`() {
        listOf(null, "", "   ").forEach {
            val fresh = SendCounterpartyRequestTest()
            assertEquals(SendOutcome.InvalidContact, fresh.send(email = it))
            assertTrue(fresh.requests.all.isEmpty())
            assertTrue(fresh.out.links.isEmpty())
        }
    }

    @Test
    fun `INV-AC-18554 no tenant value gives the configured default`() {
        send()
        assertEquals(T0.plus(Duration.ofDays(7)), out.links.single().expiry)
    }

    @Test
    fun `INV-AC-18554 tenant values 1 14 and 30 days give that validity`() {
        listOf(1L, 14L, 30L).forEach {
            val fresh = SendCounterpartyRequestTest()
            fresh.send(tenantDays = it)
            assertEquals(T0.plus(Duration.ofDays(it)), fresh.out.links.single().expiry, "$it")
        }
    }

    @Test
    fun `INV-AC-18554 tenant values 31 0 and negative refuse and nothing is stored or emitted`() {
        listOf(31L, 0L, -1L, -30L).forEach {
            val fresh = SendCounterpartyRequestTest()
            assertEquals(SendOutcome.InvalidValidity, fresh.send(tenantDays = it), "$it")
            assertTrue(fresh.requests.all.isEmpty(), "$it")
            assertTrue(fresh.out.links.isEmpty(), "$it")
        }
    }

    @Test
    fun `INV-AC-18554 other limits change the result so no number of days is written in the code`() {
        val a = SendCounterpartyRequestTest()
        a.send(limits = Limits(5, 10))
        assertEquals(T0.plus(Duration.ofDays(5)), a.out.links.single().expiry)
        val b = SendCounterpartyRequestTest()
        b.send(limits = Limits(5, 10), tenantDays = 10)
        assertEquals(T0.plus(Duration.ofDays(10)), b.out.links.single().expiry)
        val c = SendCounterpartyRequestTest()
        assertEquals(SendOutcome.InvalidValidity, c.send(limits = Limits(5, 10), tenantDays = 11))
        assertTrue(c.requests.all.isEmpty())
        assertTrue(c.out.links.isEmpty())
        val d = SendCounterpartyRequestTest()
        assertEquals(SendOutcome.InvalidValidity, d.send(limits = Limits(40, 30)))
    }
}
