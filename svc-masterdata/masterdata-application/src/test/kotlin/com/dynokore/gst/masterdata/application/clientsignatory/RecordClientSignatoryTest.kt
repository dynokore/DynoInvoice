package com.dynokore.gst.masterdata.application.clientsignatory

import com.dynokore.gst.masterdata.application.permission.TenantPermission
import com.dynokore.gst.masterdata.application.request.LinkValidityLimits
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

private val NOW: Instant = Instant.parse("2026-11-16T00:00:00Z")

private val FIXED =
    object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = NOW
    }

/** The records and the outbox rows of one tenant, restored together when a transaction fails (all-or-nothing). */
private class World : TransactionRunner {
    val records = linkedMapOf<String, ClientSignatory>()
    val outbox = mutableListOf<Pair<String, ClientSignatoryLinkRequest>>()
    var failAfterInsert = false
    private var seq = 0

    override fun <T> inTransaction(block: () -> T): T {
        val savedRecords = LinkedHashMap(records)
        val savedOutbox = outbox.toList()
        val savedSeq = seq
        return try {
            block()
        } catch (e: Throwable) {
            records.clear()
            records.putAll(savedRecords)
            outbox.clear()
            outbox.addAll(savedOutbox)
            seq = savedSeq
            throw e
        }
    }

    val store =
        object : ClientSignatories {
            override fun findByClientAndEmail(
                clientRef: String,
                email: String,
            ) = records.values.firstOrNull { it.clientRef == clientRef && it.email == email }

            override fun findByRequestEventId(requestEventId: String) =
                records.values.firstOrNull { it.requestEventId == requestEventId }

            override fun insert(signatory: ClientSignatory) {
                records[signatory.signatoryRef] = signatory
            }

            override fun saveRequestEventId(
                signatoryRef: String,
                requestEventId: String,
            ) {
                records[signatoryRef] = records.getValue(signatoryRef).copy(requestEventId = requestEventId)
            }

            override fun saveLinkStatus(
                signatoryRef: String,
                status: LinkStatus,
            ) {
                records[signatoryRef] = records.getValue(signatoryRef).copy(linkStatus = status)
            }

            override fun nextRef() = "SIG-${++seq}"
        }

    val publisher =
        ClientSignatoryLinkPublisher { request ->
            if (failAfterInsert) error("outbox is down")
            val id = "evt-${outbox.size + 1}-${request.signatoryRef}"
            outbox += id to request
            id
        }
}

private val LIMITS =
    object : LinkValidityLimits {
        override val defaultDays = 7L
        override val maxDays = 30L
    }

private const val OPERATOR = "identity:P-OP"

class RecordClientSignatoryTest {
    private val world = World()
    private var holders = setOf(OPERATOR to RecordClientSignatory.PERMISSION_KEY)
    private val permission = TenantPermission { person, key -> (person to key) in holders }
    private val record = RecordClientSignatory(world, world.store, permission, LIMITS, FIXED, world.publisher)
    private val applyOutcome = ApplyLinkOutcome(world, world.store)

    private fun command(
        consentRef: String? = "CNS-77",
        clientRef: String = "CLI-204",
        email: String = "wm.tan@example.com.sg",
        mobile: String? = null,
        caller: String = OPERATOR,
    ) = RecordClientSignatoryCommand(caller, clientRef, "Tan Wei Ming", email, mobile, consentRef)

    private fun recorded(outcome: RecordClientSignatoryOutcome) = outcome as RecordClientSignatoryOutcome.Recorded

    @Test
    fun `INV-AC-17444 a valid request stores one PENDING record and one SignedLinkRequested with the record id`() {
        val result = recorded(record.handle(command(mobile = "+6591234567")))
        assertTrue(result.created)
        assertEquals(1, world.records.size)
        val stored = world.records.values.single()
        assertEquals(LinkStatus(LinkState.PENDING), stored.linkStatus)
        assertEquals(1, world.outbox.size)
        val (eventId, event) = world.outbox.single()
        assertEquals(stored.signatoryRef, event.sourceRecordId)
        assertEquals(stored.signatoryRef, event.signatoryRef)
        assertEquals(eventId, stored.requestEventId)
        assertEquals("CLI-204", event.clientRef)
        assertEquals("CNS-77", event.consentRef)
        assertEquals("CNS-77", event.subjectReference)
        assertEquals("EMAIL", event.channel)
        assertEquals("client-signatory-consent", event.purpose)
        assertEquals("+6591234567", event.mobileNumber)
        assertEquals(NOW.plus(Duration.ofDays(7)), event.expiry)
    }

    @Test
    fun `INV-AC-17444 the same clientRef and signatoryEmail again gives the existing record and no second event`() {
        val first = recorded(record.handle(command()))
        val second = recorded(record.handle(command()))
        assertFalse(second.created)
        assertEquals(first.signatory.signatoryRef, second.signatory.signatoryRef)
        assertEquals(1, world.records.size)
        assertEquals(1, world.outbox.size)
    }

    @Test
    fun `INV-AC-17444 another client or another email is another record with its own event`() {
        record.handle(command())
        record.handle(command(clientRef = "CLI-205"))
        record.handle(command(email = "other@example.com.sg"))
        assertEquals(3, world.records.size)
        assertEquals(3, world.outbox.size)
        assertEquals(
            3,
            world.outbox
                .map { it.first }
                .toSet()
                .size,
        )
    }

    @Test
    fun `INV-AC-17444 a failure after the record insert rolls the record back with its event`() {
        world.failAfterInsert = true
        assertThrows(IllegalStateException::class.java) { record.handle(command()) }
        assertTrue(world.records.isEmpty())
        assertTrue(world.outbox.isEmpty())
    }

    @Test
    fun `INV-AC-17444 a failure after the event was written rolls the event back with its record`() {
        val failing =
            RecordClientSignatory(
                world,
                object : ClientSignatories by world.store {
                    override fun saveRequestEventId(
                        signatoryRef: String,
                        requestEventId: String,
                    ) = error("update failed")
                },
                permission,
                LIMITS,
                FIXED,
                world.publisher,
            )
        assertThrows(IllegalStateException::class.java) { failing.handle(command()) }
        assertTrue(world.records.isEmpty())
        assertTrue(world.outbox.isEmpty())
    }

    @Test
    fun `INV-AC-17444 a request without consentRef is refused and stores nothing`() {
        listOf(null, "", "   ").forEach {
            assertEquals(RecordClientSignatoryOutcome.Invalid("consentRef"), record.handle(command(consentRef = it)))
        }
        assertTrue(world.records.isEmpty())
        assertTrue(world.outbox.isEmpty())
    }

    @Test
    fun `INV-AC-17444 a caller without the key client-signatory dot record is forbidden and nothing is stored`() {
        holders =
            setOf(OPERATOR to "consent.grant", OPERATOR to "exception.defer", OPERATOR to "client-signatory.records")
        assertEquals(RecordClientSignatoryOutcome.Forbidden, record.handle(command()))
        holders = setOf(OPERATOR to "client-signatories.record", OPERATOR to "Client-signatory.record")
        assertEquals(RecordClientSignatoryOutcome.Forbidden, record.handle(command()))
        holders = emptySet()
        assertEquals(RecordClientSignatoryOutcome.Forbidden, record.handle(command()))
        assertTrue(world.records.isEmpty())
        assertTrue(world.outbox.isEmpty())
    }

    @Test
    fun `INV-AC-17444 the key decided is exactly client-signatory dot record`() {
        assertEquals("client-signatory.record", RecordClientSignatory.PERMISSION_KEY)
        holders = setOf("identity:OTHER" to RecordClientSignatory.PERMISSION_KEY)
        assertEquals(RecordClientSignatoryOutcome.Forbidden, record.handle(command()))
    }

    private fun requestEventId() =
        world.records.values
            .single()
            .requestEventId!!

    private fun state() =
        world.records.values
            .single()
            .linkStatus

    @Test
    fun `INV-AC-17444 an ISSUED outcome sets ISSUED`() {
        record.handle(command())
        assertEquals(OutcomeResult.APPLIED, applyOutcome.apply(requestEventId(), LinkStatus(LinkState.ISSUED)))
        assertEquals(LinkStatus(LinkState.ISSUED), state())
    }

    @Test
    fun `INV-AC-17444 a REFUSED outcome with MOBILE_NUMBER_REQUIRED sets REFUSED with that reason`() {
        record.handle(command())
        val refused = LinkStatus(LinkState.REFUSED, LinkRefusalReason.MOBILE_NUMBER_REQUIRED)
        assertEquals(OutcomeResult.APPLIED, applyOutcome.apply(requestEventId(), refused))
        assertEquals(refused, state())
    }

    @Test
    fun `INV-AC-17444 the same outcome delivered twice changes nothing the second time`() {
        record.handle(command())
        applyOutcome.apply(requestEventId(), LinkStatus(LinkState.ISSUED))
        val before = world.records.toMap()
        assertEquals(OutcomeResult.NO_CHANGE, applyOutcome.apply(requestEventId(), LinkStatus(LinkState.ISSUED)))
        assertEquals(before, world.records.toMap())
    }

    @Test
    fun `INV-AC-17444 an outcome for an unknown request id changes no record`() {
        record.handle(command())
        val before = world.records.toMap()
        assertEquals(
            OutcomeResult.UNKNOWN_REQUEST,
            applyOutcome.apply("evt-unknown", LinkStatus(LinkState.ISSUED)),
        )
        assertEquals(before, world.records.toMap())
    }

    @Test
    fun `INV-AC-17444 a stale outcome never moves a record from ISSUED back to PENDING or to REFUSED`() {
        record.handle(command())
        applyOutcome.apply(requestEventId(), LinkStatus(LinkState.ISSUED))
        assertEquals(OutcomeResult.NO_CHANGE, applyOutcome.apply(requestEventId(), LinkStatus(LinkState.PENDING)))
        assertEquals(
            OutcomeResult.NO_CHANGE,
            applyOutcome.apply(requestEventId(), LinkStatus(LinkState.REFUSED, LinkRefusalReason.CONSENT_NOT_LIVE)),
        )
        assertEquals(LinkStatus(LinkState.ISSUED), state())
    }

    @Test
    fun `INV-AC-17444 a reason code goes with REFUSED only`() {
        assertThrows(IllegalArgumentException::class.java) {
            LinkStatus(LinkState.ISSUED, LinkRefusalReason.CONSENT_NOT_LIVE)
        }
        assertThrows(IllegalArgumentException::class.java) { LinkStatus(LinkState.REFUSED) }
        assertNull(LinkStatus(LinkState.PENDING).reasonCode)
    }
}
