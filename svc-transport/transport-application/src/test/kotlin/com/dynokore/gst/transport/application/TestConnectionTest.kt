package com.dynokore.gst.transport.application

import com.dynokore.gst.transport.domain.TenantRef
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class TestConnectionTest {
    private val t = TenantRef("t-1")
    private val now = Instant.parse("2026-07-01T00:00:00Z")
    private var stored: ConnectionState? = ConnectionState.UP
    private val events = mutableListOf<Pair<ConnectionState, String?>>()
    private val recorded = mutableListOf<Boolean>()

    private fun uc(
        outcome: ProbeOutcome,
        breaker: BreakerState,
    ) = TestConnection(
        { _, _ -> outcome },
        { _, ok ->
            recorded += ok
            breaker
        },
        object : ConnectionStatusStore {
            override fun stored(
                tenant: TenantRef,
                connectionRef: String,
            ) = stored

            override fun save(
                tenant: TenantRef,
                connectionRef: String,
                state: ConnectionState,
            ) {
                stored = state
            }
        },
        { _, _, s, r -> events += s to r },
        Clock.fixed(now, ZoneOffset.UTC),
    )

    @Test
    fun `success class is returned and the breaker records a success`() {
        val r = uc(ProbeOutcome(TestClass.SUCCESS), BreakerState.CLOSED).test(t, "CON-1")
        r.classification shouldBe TestClass.SUCCESS
        r.detail shouldBe null
        r.checkedAt shouldBe now
        recorded shouldBe listOf(true)
    }

    @Test
    fun `unreachable class carries its code and records a failure`() {
        val r = uc(ProbeOutcome(TestClass.UNREACHABLE, "CONNECT-TIMEOUT"), BreakerState.CLOSED).test(t, "CON-1")
        r.classification shouldBe TestClass.UNREACHABLE
        r.detail shouldBe "CONNECT-TIMEOUT"
        recorded shouldBe listOf(false)
    }

    @Test
    fun `authentication refused class is returned`() {
        uc(ProbeOutcome(TestClass.AUTHENTICATION_REFUSED, "CREDENTIAL-REFUSED"), BreakerState.CLOSED)
            .test(t, "CON-1")
            .classification shouldBe TestClass.AUTHENTICATION_REFUSED
    }

    @Test
    fun `unexpected response class is returned`() {
        uc(ProbeOutcome(TestClass.UNEXPECTED_RESPONSE, "BAD-STATUS"), BreakerState.CLOSED)
            .test(t, "CON-1")
            .classification shouldBe TestClass.UNEXPECTED_RESPONSE
    }

    @Test
    fun `closed breaker gives UP, half-open DEGRADED, open DOWN`() {
        uc(ProbeOutcome(TestClass.SUCCESS), BreakerState.CLOSED).test(t, "C").state shouldBe ConnectionState.UP
        uc(ProbeOutcome(TestClass.SUCCESS), BreakerState.HALF_OPEN).test(t, "C").state shouldBe ConnectionState.DEGRADED
        uc(ProbeOutcome(TestClass.SUCCESS), BreakerState.OPEN).test(t, "C").state shouldBe ConnectionState.DOWN
    }

    @Test
    fun `look-alike the state is not decided from the class alone`() {
        // a failure class with a closed breaker is UP; a success class with an open breaker is DOWN
        uc(ProbeOutcome(TestClass.UNREACHABLE, "CONNECT-TIMEOUT"), BreakerState.CLOSED).test(t, "C").state shouldBe
            ConnectionState.UP
        uc(ProbeOutcome(TestClass.SUCCESS), BreakerState.OPEN).test(t, "C").state shouldBe ConnectionState.DOWN
    }

    @Test
    fun `a changed state is stored and requests one event with the failure code`() {
        uc(ProbeOutcome(TestClass.UNREACHABLE, "CONNECT-TIMEOUT"), BreakerState.OPEN).test(t, "C")
        stored shouldBe ConnectionState.DOWN
        events shouldBe listOf(ConnectionState.DOWN to "CONNECT-TIMEOUT")
    }

    @Test
    fun `a repeat with an unchanged state requests no event`() {
        val c = uc(ProbeOutcome(TestClass.UNREACHABLE, "CONNECT-TIMEOUT"), BreakerState.OPEN)
        c.test(t, "C")
        c.test(t, "C")
        events.size shouldBe 1
        uc(ProbeOutcome(TestClass.SUCCESS), BreakerState.OPEN).test(t, "C")
        events.size shouldBe 1
    }

    @Test
    fun `a first test with no stored state requests an event`() {
        stored = null
        uc(ProbeOutcome(TestClass.SUCCESS), BreakerState.CLOSED).test(t, "C")
        events shouldBe listOf(ConnectionState.UP to null)
    }

    @Test
    fun `a detail that is not a code is replaced by UNKNOWN and a SUCCESS detail is dropped`() {
        val free = uc(ProbeOutcome(TestClass.UNREACHABLE, "connection timed out"), BreakerState.OPEN).test(t, "C")
        free.detail shouldBe "UNKNOWN"
        events shouldBe listOf(ConnectionState.DOWN to "UNKNOWN")
        uc(ProbeOutcome(TestClass.SUCCESS, "OK-CODE"), BreakerState.CLOSED).test(t, "C").detail shouldBe null
        recorded shouldBe listOf(false, true)
    }
}
