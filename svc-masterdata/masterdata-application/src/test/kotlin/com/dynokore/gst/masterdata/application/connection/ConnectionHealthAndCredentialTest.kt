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
import org.junit.jupiter.api.Assertions.assertNotEquals
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
    private val settings =
        HealthSettings(Duration.ofMinutes(5), failureThreshold = 3, openFor = Duration.ofSeconds(30), percentile = 95)
    private val calls = Calls()
    private val memory = Memory()
    private val published = mutableListOf<ConnectionStatusChanged>()
    private val healthKeys = mutableListOf<String>()
    private val ports = HealthPorts(records, calls)
    private val health =
        GetConnectionHealth(HealthTx, ports, { _, key ->
            healthKeys += key
            true
        }, settings, clock)
    private val evaluator = EvaluateConnectionStatus(HealthTx, ports, memory, { published += it }, settings, clock)

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
        evaluator.handle("CON-1")
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
        assertEquals(0, published.size)
        call(3, false)
        call(4, false)
        assertEquals(BreakerState.CLOSED, at(5).h.breakerState)
        assertEquals(0, published.size)
        call(6, false)
        val open = at(7).h
        assertEquals(BreakerState.OPEN, open.breakerState)
        assertEquals(ConnectionState.DOWN, open.state)
        assertEquals(0.25, open.successRate)
        assertEquals(300L, open.latencyMillis)
        assertEquals(t0.plusSeconds(6), open.lastFailure!!.at)
        assertEquals(TestClass.UNREACHABLE, open.lastFailure!!.failure.classification)
        assertEquals(listOf(ConnectionState.DOWN), published.map { it.state })
        at(8)
        assertEquals(1, published.size)
        assertEquals(ConnectionState.DEGRADED, at(40).h.state)
        call(41, true)
        assertEquals(ConnectionState.UP, at(42).h.state)
        assertEquals(
            listOf(ConnectionState.DOWN, ConnectionState.DEGRADED, ConnectionState.UP),
            published.map { it.state },
        )
    }

    @Test
    fun `INV-AC-18782 the first evaluation in state DOWN requests 0 and records DOWN and a later UP requests 1`() {
        call(1, false)
        call(2, false)
        call(3, false)
        clock.now = t0.plusSeconds(4)
        evaluator.handle("CON-1")
        assertEquals(0, published.size)
        assertEquals(ConnectionState.DOWN, memory.m["CON-1"])
        evaluator.handle("CON-1")
        assertEquals(0, published.size)
        call(5, true)
        clock.now = t0.plusSeconds(6)
        evaluator.handle("CON-1")
        assertEquals(listOf(ConnectionState.UP), published.map { it.state })
        evaluator.handle("CON-1")
        assertEquals(1, published.size)
    }

    @Test
    fun `INV-AC-18782 a read while the state differs from the recorded one requests 0 and leaves memory unchanged`() {
        call(1, true)
        clock.now = t0.plusSeconds(2)
        evaluator.handle("CON-1")
        val before = memory.m.toMap()
        call(3, false)
        call(4, false)
        call(5, false)
        clock.now = t0.plusSeconds(6)
        val found = health.handle(caller, "CON-1") as GetConnectionHealthOutcome.Found
        assertEquals(ConnectionState.DOWN, found.health.state)
        assertEquals(0, published.size)
        assertEquals(before, memory.m)
    }

    @Test
    fun `INV-AC-18782 an adapter that has not yet checked requests none and records no baseline`() {
        clock.now = t0.plusSeconds(1)
        evaluator.handle("CON-1")
        assertTrue(published.isEmpty())
        assertTrue(memory.m.isEmpty())
    }

    private fun latencyOf(vararg millis: Long): Long? {
        millis.forEachIndexed { i, ms -> calls.all += CallRecord(t0.plusSeconds(i + 1L), Duration.ofMillis(ms)) }
        return at(60).h.latencyMillis
    }

    @Test
    fun `INV-AC-18782 latency is the 95th percentile by nearest rank and not the mean`() {
        assertEquals(1000L, latencyOf(100, 200, 300, 400, 1000))
    }

    @Test
    fun `INV-AC-18782 latency of the durations 1 to 20 ms is 19`() {
        assertEquals(19L, latencyOf(*LongArray(20) { it + 1L }))
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
        assertNull(h.latencyMillis)
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
        call(1, true)
        val denied = GetConnectionHealth(HealthTx, ports, { _, _ -> false }, settings, clock)
        assertEquals(GetConnectionHealthOutcome.Forbidden, denied.handle(caller, "CON-1"))
        assertEquals(GetConnectionHealthOutcome.Unknown, health.handle(caller, "CON-9"))
        assertTrue(memory.m.isEmpty() && published.isEmpty())
    }

    @Test
    fun `INV-AC-18782 the permission keys equal the contract strings and the health read asks exactly its key`() {
        assertEquals("connection-health.view", GetConnectionHealth.PERMISSION_KEY)
        assertEquals("connection-credential.rotate", RotateCredential.PERMISSION_KEY)
        listOf(
            "connection-health.views",
            "connection.test",
            "Connection-Health.View",
            "connection-health.view ",
        ).forEach {
            assertNotEquals(it, GetConnectionHealth.PERMISSION_KEY)
        }
        health.handle(caller, "CON-1")
        assertEquals(listOf("connection-health.view"), healthKeys)
        rotation(Duration.ofMinutes(1)).handle(caller, "CON-1", "cred-new", null)
        assertEquals(listOf("connection-credential.rotate"), rotateKeys)
    }

    private val secrets = Secrets(setOf("cred-new", "cred-newer"))
    private val states = States()

    private val rotateKeys = mutableListOf<String>()

    private fun rotation(overlap: Duration?) =
        RotateCredential(
            HealthTx,
            RotationPorts(records, states, secrets, { _, _, _ -> }),
            { _, key ->
                rotateKeys += key
                true
            },
            RotationSettings(overlap),
            clock,
        )

    private val gate = CredentialGate(HealthTx, records, states, clock)

    @Test
    fun `INV-AC-18782 an in-flight call finishes on the old credential and no call fails`() {
        val inFlight = gate.begin("CON-1")
        assertEquals("cred-old", inFlight)
        val out = rotation(Duration.ofMinutes(10)).handle(caller, "CON-1", "cred-new", null)
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
        rotation(Duration.ofMinutes(10)).handle(caller, "CON-1", "cred-new", null)
        clock.now = t0.plusSeconds(1)
        assertTrue(gate.accepts("CON-1", inFlight))
    }

    @Test
    fun `INV-AC-18782 a repeat of the same rotation returns the original outcome and starts no second window`() {
        val first =
            rotation(
                Duration.ofMinutes(10),
            ).handle(caller, "CON-1", "cred-new", null) as RotateCredentialOutcome.Rotated
        clock.now = t0.plusSeconds(120)
        val again =
            rotation(
                Duration.ofMinutes(10),
            ).handle(caller, "CON-1", "cred-new", null) as RotateCredentialOutcome.Rotated
        assertTrue(again.repeat)
        assertEquals(first.rotation, again.rotation)
        assertEquals(t0.plus(Duration.ofMinutes(10)), states.find("CON-1")!!.overlapEnd)
        assertEquals(1, secrets.reads)
    }

    @Test
    fun `INV-AC-18782 rotations to A B A and B again perform four rotations and a retry of the last reads no secret`() {
        val rotate = rotation(Duration.ofMinutes(10))
        listOf("cred-new", "cred-newer", "cred-new", "cred-newer").forEachIndexed { i, ref ->
            val out = rotate.handle(caller, "CON-1", ref, null) as RotateCredentialOutcome.Rotated
            assertFalse(out.repeat, "rotation ${i + 1}")
            assertEquals(i + 1, secrets.reads)
        }
        val end = states.find("CON-1")!!.overlapEnd
        val retry = rotate.handle(caller, "CON-1", "cred-newer", null) as RotateCredentialOutcome.Rotated
        assertTrue(retry.repeat)
        assertEquals(4, secrets.reads)
        assertEquals(end, states.find("CON-1")!!.overlapEnd)
    }

    @Test
    fun `INV-AC-18782 rotation is refused while the overlap is unset and when the replacement does not resolve`() {
        assertEquals(
            RotateCredentialOutcome.OverlapNotConfigured,
            rotation(null).handle(caller, "CON-1", "cred-new", null),
        )
        assertNull(states.find("CON-1"))
        assertEquals(0, secrets.reads)
        assertEquals(
            RotateCredentialOutcome.ReplacementUnavailable,
            rotation(Duration.ofMinutes(1)).handle(caller, "CON-1", "missing", null),
        )
        assertEquals(
            RotateCredentialOutcome.SameAsCurrent,
            rotation(Duration.ofMinutes(1)).handle(caller, "CON-1", "cred-old", null),
        )
        assertEquals(
            RotateCredentialOutcome.Unknown,
            rotation(Duration.ofMinutes(1)).handle(caller, "CON-9", "cred-new", null),
        )
        val denied =
            RotateCredential(
                HealthTx,
                RotationPorts(records, states, secrets, { _, _, _ -> }),
                { _, _ -> false },
                RotationSettings(Duration.ofMinutes(1)),
                clock,
            )
        assertEquals(RotateCredentialOutcome.Forbidden, denied.handle(caller, "CON-1", "cred-new", null))
        assertNull(states.find("CON-1"))
    }

    @Test
    fun `INV-AC-18782 a second rotation drops the first previous reference`() {
        rotation(Duration.ofMinutes(10)).handle(caller, "CON-1", "cred-new", null)
        rotation(Duration.ofMinutes(10)).handle(caller, "CON-1", "cred-newer", null)
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
