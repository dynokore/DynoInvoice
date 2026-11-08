package com.dynokore.gst.masterdata.application.connection

import com.dynokore.gst.masterdata.domain.connection.BreakerState
import com.dynokore.gst.masterdata.domain.connection.CallFailure
import com.dynokore.gst.masterdata.domain.connection.CallRecord
import com.dynokore.gst.masterdata.domain.connection.ConnectionRecord
import com.dynokore.gst.masterdata.domain.connection.ConnectionState
import com.dynokore.gst.masterdata.domain.connection.EnvironmentTag
import com.dynokore.gst.masterdata.domain.connection.HealthSettings
import com.dynokore.gst.masterdata.domain.connection.LastFailure
import com.dynokore.gst.masterdata.domain.connection.TestClass
import com.dynokore.gst.masterdata.domain.identity.PersonRef
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

private object HealthTx : TransactionRunner {
    override fun <T> inTransaction(block: () -> T): T = block()
}

private class MutableClock(
    var now: Instant,
) : Clock() {
    override fun getZone(): ZoneOffset = ZoneOffset.UTC

    override fun withZone(zone: java.time.ZoneId?) = this

    override fun instant() = now
}

private class HealthRecords : ConnectionStore {
    val all = linkedMapOf<String, ConnectionRecord>()

    override fun find(connectionRef: String) = all[connectionRef]

    override fun findByServiceAndTag(
        externalService: String,
        environmentTag: EnvironmentTag,
    ) = null

    override fun save(record: ConnectionRecord) {
        all[record.connectionRef] = record
    }

    override fun nextRef() = "CON-1"
}

private class Calls : CallRecords {
    val all = mutableListOf<CallRecord>()

    override fun between(
        connectionRef: String,
        from: Instant,
        to: Instant,
    ) = all.filter { !it.at.isBefore(from) && !it.at.isAfter(to) }.sortedBy { it.at }

    override fun lastFailure(connectionRef: String) =
        all.filter { !it.succeeded }.maxByOrNull { it.at }?.let { LastFailure(it.at, it.failure!!) }

    override fun hasAny(connectionRef: String) = all.isNotEmpty()
}

private class Memory : ConnectionStateMemory {
    val m = mutableMapOf<String, ConnectionState>()

    override fun lastRequested(connectionRef: String) = m[connectionRef]

    override fun remember(
        connectionRef: String,
        state: ConnectionState,
    ) {
        m[connectionRef] = state
    }
}

private class Secrets(
    val known: Set<String>,
) : SecretStore {
    var reads = 0

    override fun read(credentialRef: String): CharArray? {
        reads++
        return if (credentialRef in known) "s3cret".toCharArray() else null
    }
}

private class States : CredentialStateStore {
    val m = mutableMapOf<String, CredentialState>()

    override fun find(connectionRef: String) = m[connectionRef]

    override fun save(state: CredentialState) {
        m[state.connectionRef] = state
    }
}

private class Reminded : ReminderLog {
    val s = mutableSetOf<Triple<String, String, Int>>()

    override fun wasReminded(
        connectionRef: String,
        credentialRef: String,
        offsetDays: Int,
    ) = Triple(connectionRef, credentialRef, offsetDays) in s

    override fun markReminded(
        connectionRef: String,
        credentialRef: String,
        offsetDays: Int,
    ) {
        s += Triple(connectionRef, credentialRef, offsetDays)
    }
}

class ConnectionHealthAndCredentialTest {
    private val t0 = Instant.parse("2026-03-20T00:00:00Z")
    private val clock = MutableClock(t0)
    private val caller = PersonRef("U-1")
    private val records =
        HealthRecords().also {
            it.save(
                ConnectionRecord(
                    "CON-1",
                    "DIRECTORY",
                    "https://d.example",
                    "cred-old",
                    EnvironmentTag.SANDBOX,
                    "masterdata-svc",
                ),
            )
        }
    private val settings = HealthSettings(Duration.ofMinutes(5), failureThreshold = 3, openFor = Duration.ofSeconds(30))
    private val calls = Calls()
    private val memory = Memory()
    private val published = mutableListOf<ConnectionStatusChanged>()
    private val health =
        GetConnectionHealth(
            HealthTx,
            HealthPorts(records, calls, memory),
            { published += it },
            { true },
            settings,
            clock,
        )

    private fun call(
        secondsAfterStart: Long,
        ok: Boolean,
    ) {
        calls.all +=
            CallRecord(
                t0.plusSeconds(secondsAfterStart),
                Duration.ofMillis(if (ok) 100 else 300),
                if (ok) null else CallFailure(TestClass.UNREACHABLE, "CONNECT-TIMEOUT"),
            )
    }

    private fun at(seconds: Long): ConnectionHealthSnapshot {
        clock.now = t0.plusSeconds(seconds)
        val outcome = health.handle(caller, "CON-1")
        return ConnectionHealthSnapshot((outcome as GetConnectionHealthOutcome.Found).health)
    }

    private class ConnectionHealthSnapshot(
        val h: com.dynokore.gst.masterdata.domain.connection.ConnectionHealth,
    )

    @Test
    fun `INV-AC-18782 repeated failures open the breaker with figures and one status change is requested per change`() {
        call(1, true)
        assertEquals(ConnectionState.UP, at(2).h.state)
        assertEquals(1, published.size)
        call(3, false)
        call(4, false)
        assertEquals(BreakerState.CLOSED, at(5).h.breakerState)
        assertEquals(1, published.size)
        call(6, false)
        val open = at(7).h
        assertEquals(BreakerState.OPEN, open.breakerState)
        assertEquals(ConnectionState.DOWN, open.state)
        assertEquals(0.25, open.successRate)
        assertEquals(Duration.ofMillis(250), open.latency)
        assertEquals(t0.plusSeconds(6), open.lastFailure!!.at)
        assertEquals(TestClass.UNREACHABLE, open.lastFailure!!.failure.classification)
        assertEquals(listOf(ConnectionState.UP, ConnectionState.DOWN), published.map { it.state })
        at(8)
        assertEquals(2, published.size)
        assertEquals(ConnectionState.DEGRADED, at(40).h.state)
        call(41, true)
        assertEquals(ConnectionState.UP, at(42).h.state)
        assertEquals(
            listOf(ConnectionState.UP, ConnectionState.DOWN, ConnectionState.DEGRADED, ConnectionState.UP),
            published.map { it.state },
        )
    }

    @Test
    fun `INV-AC-18782 a single failure after a success run leaves the breaker closed`() {
        call(1, true)
        call(2, true)
        call(3, false)
        assertEquals(BreakerState.CLOSED, at(4).h.breakerState)
    }

    @Test
    fun `INV-AC-18782 before the first call the figures are absent and no status change is requested`() {
        val h = at(1).h
        assertNull(h.successRate)
        assertNull(h.latency)
        assertTrue(published.isEmpty())
    }

    @Test
    fun `INV-AC-18782 the breaker state maps by equality and an unknown value is refused`() {
        assertEquals(ConnectionState.UP, ConnectionState.of(BreakerState.parse("CLOSED")!!))
        assertEquals(ConnectionState.DEGRADED, ConnectionState.of(BreakerState.parse("HALF_OPEN")!!))
        assertEquals(ConnectionState.DOWN, ConnectionState.of(BreakerState.parse("OPEN")!!))
        listOf("closed", "Open", "HALF-OPEN", "OPEN ", "", "UNKNOWN").forEach { assertNull(BreakerState.parse(it), it) }
    }

    @Test
    fun `INV-AC-18782 a caller without connection view permission learns nothing`() {
        val denied =
            GetConnectionHealth(
                HealthTx,
                HealthPorts(records, calls, memory),
                { published += it },
                { false },
                settings,
                clock,
            )
        assertEquals(GetConnectionHealthOutcome.Forbidden, denied.handle(caller, "CON-1"))
        assertEquals(GetConnectionHealthOutcome.Unknown, health.handle(caller, "CON-9"))
    }

    private val secrets = Secrets(setOf("cred-new", "cred-newer"))
    private val states = States()

    private fun rotation(overlap: Duration?) =
        RotateCredential(HealthTx, RotationPorts(records, states, secrets), { true }, RotationSettings(overlap), clock)

    private val gate = CredentialGate(HealthTx, records, states, clock)

    @Test
    fun `INV-AC-18782 an in-flight call finishes on the old credential and no call fails`() {
        val inFlight = gate.begin("CON-1")
        assertEquals("cred-old", inFlight)
        val out = rotation(Duration.ofMinutes(10)).handle(caller, "CON-1", "cred-new")
        val rotated = assertInstanceOf(RotateCredentialOutcome.Rotated::class.java, out)
        assertEquals(t0.plus(Duration.ofMinutes(10)), rotated.rotation.overlapEnd)
        assertTrue(gate.accepts("CON-1", inFlight!!), "the call started before the rotation finishes on its credential")
        assertEquals("cred-new", gate.begin("CON-1"))
        clock.now = t0.plus(Duration.ofMinutes(10))
        assertFalse(gate.accepts("CON-1", "cred-old"), "after the overlap end the previous reference is refused")
        assertNull(states.find("CON-1")!!.previousRef)
        assertTrue(gate.accepts("CON-1", "cred-new"))
    }

    @Test
    fun `INV-AC-18782 a rotation that withdraws the old credential at once is detected by the overlap comparison`() {
        val inFlight = gate.begin("CON-1")!!
        rotation(Duration.ofMinutes(10)).handle(caller, "CON-1", "cred-new")
        clock.now = t0.plusSeconds(1)
        assertTrue(gate.accepts("CON-1", inFlight))
    }

    @Test
    fun `INV-AC-18782 a repeat of the same rotation returns the original outcome and starts no second window`() {
        val first =
            rotation(
                Duration.ofMinutes(10),
            ).handle(caller, "CON-1", "cred-new") as RotateCredentialOutcome.Rotated
        clock.now = t0.plusSeconds(120)
        val again =
            rotation(
                Duration.ofMinutes(10),
            ).handle(caller, "CON-1", "cred-new") as RotateCredentialOutcome.Rotated
        assertTrue(again.repeat)
        assertEquals(first.rotation, again.rotation)
        assertEquals(t0.plus(Duration.ofMinutes(10)), states.find("CON-1")!!.overlapEnd)
        assertEquals(1, secrets.reads)
    }

    @Test
    fun `INV-AC-18782 rotation is refused while the overlap is unset and when the replacement does not resolve`() {
        assertEquals(RotateCredentialOutcome.OverlapNotConfigured, rotation(null).handle(caller, "CON-1", "cred-new"))
        assertNull(states.find("CON-1"))
        assertEquals(0, secrets.reads)
        assertEquals(
            RotateCredentialOutcome.ReplacementUnavailable,
            rotation(Duration.ofMinutes(1)).handle(caller, "CON-1", "missing"),
        )
        assertEquals(
            RotateCredentialOutcome.SameAsCurrent,
            rotation(Duration.ofMinutes(1)).handle(caller, "CON-1", "cred-old"),
        )
        assertEquals(
            RotateCredentialOutcome.Unknown,
            rotation(Duration.ofMinutes(1)).handle(caller, "CON-9", "cred-new"),
        )
        val denied =
            RotateCredential(
                HealthTx,
                RotationPorts(records, states, secrets),
                { false },
                RotationSettings(Duration.ofMinutes(1)),
                clock,
            )
        assertEquals(RotateCredentialOutcome.Forbidden, denied.handle(caller, "CON-1", "cred-new"))
        assertNull(states.find("CON-1"))
    }

    @Test
    fun `INV-AC-18782 a second rotation drops the first previous reference`() {
        rotation(Duration.ofMinutes(10)).handle(caller, "CON-1", "cred-new")
        rotation(Duration.ofMinutes(10)).handle(caller, "CON-1", "cred-newer")
        assertFalse(gate.accepts("CON-1", "cred-old"))
        assertTrue(gate.accepts("CON-1", "cred-new"))
    }

    private val reminded = Reminded()
    private val reminders = mutableListOf<ConnectionCredentialExpiryReminderRequested>()

    private fun expiry(
        offsets: List<Int>,
        inDays: Long,
    ) = CheckCredentialExpiry(
        HealthTx,
        { listOf(CredentialExpiry("CON-1", "cred-old", t0.plus(Duration.ofDays(inDays)))) },
        { offsets },
        reminded,
        { reminders += it },
        clock,
    )

    @Test
    fun `INV-AC-18782 expiry in 10 days with offsets 14 and 3 requests exactly one reminder and a second run none`() {
        val check = expiry(listOf(14, 3), 10)
        assertEquals(listOf(14), check.run().map { it.offsetDays })
        assertTrue(check.run().isEmpty())
        assertEquals(1, reminders.size)
        clock.now = t0.plus(Duration.ofDays(7))
        assertEquals(listOf(3), check.run().map { it.offsetDays })
    }

    @Test
    fun `INV-AC-18782 with the tenant offsets 10 and 3 the same expiry requests one reminder with offset 10`() {
        assertEquals(listOf(10), expiry(listOf(10, 3), 10).run().map { it.offsetDays })
        assertEquals("cred-old", reminders.single().credentialRef)
    }

    @Test
    fun `INV-AC-18782 no reminder before the offset day and none after the credential expired`() {
        assertTrue(expiry(listOf(14, 3), 20).run().isEmpty())
        assertTrue(expiry(listOf(14, 3), -1).run().isEmpty())
    }
}
