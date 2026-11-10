package com.dynokore.gst.masterdata.application.connection

import com.dynokore.gst.masterdata.domain.connection.ConnectionRecord
import com.dynokore.gst.masterdata.domain.connection.EnvironmentTag
import com.dynokore.gst.masterdata.domain.identity.PersonRef
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** A transaction that rolls the fakes back when the block throws. */
private class Journal {
    val saved = linkedMapOf<String, ConnectionRecord>()
    val events = mutableListOf<String>()
    val recorded = mutableListOf<Triple<String, String, Instant?>>()
    var tx = 0
    var failRecord = false
}

private class RollbackTx(
    private val j: Journal,
) : TransactionRunner {
    override fun <T> inTransaction(block: () -> T): T {
        val savedBefore = LinkedHashMap(j.saved)
        val recordedBefore = j.recorded.toList()
        j.tx++
        try {
            return block()
        } catch (e: RuntimeException) {
            j.saved.clear()
            j.saved.putAll(savedBefore)
            j.recorded.clear()
            j.recorded.addAll(recordedBefore)
            throw e
        }
    }
}

private class JournalStore(
    private val j: Journal,
) : ConnectionStore {
    override fun find(connectionRef: String) = j.saved[connectionRef]

    override fun findByServiceAndTag(
        externalService: String,
        environmentTag: EnvironmentTag,
    ) = j.saved.values.firstOrNull { it.externalService == externalService && it.environmentTag == environmentTag }

    override fun save(record: ConnectionRecord) {
        j.events += "save@tx${j.tx}"
        j.saved[record.connectionRef] = record
    }

    override fun nextRef() = "CON-${j.saved.size + 1}"
}

private class JournalExpiries(
    private val j: Journal,
) : CredentialExpiryStore {
    override fun record(
        connectionRef: String,
        credentialRef: String,
        expiresAt: Instant?,
    ) {
        j.events += "record@tx${j.tx}"
        check(!j.failRecord) { "store down" }
        j.recorded += Triple(connectionRef, credentialRef, expiresAt)
    }
}

private class JournalStates : CredentialStateStore {
    val m = mutableMapOf<String, CredentialState>()

    override fun find(connectionRef: String) = m[connectionRef]

    override fun save(state: CredentialState) {
        m[state.connectionRef] = state
    }
}

class CredentialExpiryRecordingTest {
    private val caller = PersonRef("USR-1")
    private val e = Instant.parse("2027-06-30T00:00:00Z")
    private val j = Journal()
    private val tx = RollbackTx(j)
    private val store = JournalStore(j)
    private val expiries = JournalExpiries(j)
    private val states = JournalStates()
    private val clock =
        object : Clock() {
            override fun getZone() = ZoneOffset.UTC

            override fun withZone(zone: java.time.ZoneId?) = this

            override fun instant(): Instant = Instant.parse("2026-11-10T00:00:00Z")
        }
    private val readable = setOf("cred-old", "cred-new", "cred-newer")

    private fun create(
        expiresAt: Instant?,
        tag: String = "PRODUCTION",
        allow: Boolean = true,
    ) = CreateConnection(tx, store, { allow }, expiries).handle(
        CreateConnectionCommand(
            caller,
            "CHECK_GST_REGISTER",
            "https://api.example.sg",
            "cred-old",
            tag,
            "masterdata-svc",
            expiresAt,
        ),
    )

    private fun rotate(
        ref: String,
        expiresAt: Instant?,
        allow: Boolean = true,
        overlap: Duration? = Duration.ofMinutes(10),
        connection: String = "CON-1",
    ) = RotateCredential(
        tx,
        RotationPorts(store, states, { r -> if (r in readable) charArrayOf('x') else null }, expiries),
        { _, _ -> allow },
        RotationSettings(overlap),
        clock,
    ).handle(caller, connection, ref, expiresAt)

    @Test
    fun `INV-AC-19566 a create with an expiry records once with ref credential and E after save in one tx`() {
        val out = assertInstanceOf(CreateConnectionOutcome.Stored::class.java, create(e))
        assertTrue(out.created)
        assertEquals(listOf(Triple("CON-1", "cred-old", e)), j.recorded)
        assertEquals(listOf("save@tx1", "record@tx1"), j.events)
    }

    @Test
    fun `INV-AC-19566 a store whose record throws leaves no saved record`() {
        j.failRecord = true
        assertThrows(IllegalStateException::class.java) { create(e) }
        assertTrue(j.saved.isEmpty())
        assertTrue(j.recorded.isEmpty())
    }

    @Test
    fun `INV-AC-19566 a create without an expiry a repeat create and a denied create record 0 times`() {
        create(null)
        assertTrue(j.recorded.isEmpty())
        val repeat = assertInstanceOf(CreateConnectionOutcome.Stored::class.java, create(e))
        assertEquals(false, repeat.created)
        assertEquals(j.saved["CON-1"], repeat.record)
        assertTrue(j.recorded.isEmpty())
        assertEquals(CreateConnectionOutcome.Forbidden, create(e, tag = "SANDBOX", allow = false))
        assertEquals(CreateConnectionOutcome.UnknownEnvironmentTag, create(e, tag = "STAGING"))
        assertTrue(j.recorded.isEmpty())
        assertEquals(1, j.saved.size)
    }

    @Test
    fun `INV-AC-19566 a rotation with an expiry records the new reference and E and one without records null`() {
        create(null)
        assertInstanceOf(RotateCredentialOutcome.Rotated::class.java, rotate("cred-new", e))
        assertEquals(listOf(Triple("CON-1", "cred-new", e)), j.recorded)
        assertInstanceOf(RotateCredentialOutcome.Rotated::class.java, rotate("cred-newer", null))
        assertEquals(Triple("CON-1", "cred-newer", null as Instant?), j.recorded.last())
        assertEquals(2, j.recorded.size)
    }

    @Test
    fun `INV-AC-19566 a repeat rotation and every refused rotation record 0 times`() {
        create(null)
        rotate("cred-new", e)
        val before = j.recorded.size
        assertEquals(true, (rotate("cred-new", e) as RotateCredentialOutcome.Rotated).repeat)
        assertEquals(RotateCredentialOutcome.Forbidden, rotate("cred-newer", e, allow = false))
        assertEquals(RotateCredentialOutcome.OverlapNotConfigured, rotate("cred-newer", e, overlap = null))
        assertEquals(RotateCredentialOutcome.Unknown, rotate("cred-newer", e, connection = "CON-9"))
        assertEquals(RotateCredentialOutcome.ReplacementUnavailable, rotate("missing", e))
        assertEquals(before, j.recorded.size)
    }

    @Test
    fun `INV-AC-19566 a rotation to the reference already current records 0 times`() {
        create(null)
        assertEquals(RotateCredentialOutcome.SameAsCurrent, rotate("cred-old", e))
        assertTrue(j.recorded.isEmpty())
    }
}
