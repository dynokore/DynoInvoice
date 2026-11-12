package com.dynokore.gst.evidence.application.chain

import com.dynokore.gst.evidence.domain.chain.DocumentLocator
import com.dynokore.gst.evidence.domain.chain.RecordKind
import com.dynokore.platform.evidencestore.EvidenceLedger
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * INV-AC-20570: one locator row and one ACKNOWLEDGEMENT record per event, a repeat writes nothing, and an
 * acknowledgement id stored for another version is refused before any write. The appender is the real one over a
 * pending store that counts its writes (no locator of the version is stored, so a record is kept pending).
 */
class AcknowledgementRecorderTest {
    private val events = mutableListOf<String>()
    private val kinds = mutableListOf<RecordKind>()

    private inner class Locators : AcknowledgementLocatorStore {
        val rows = linkedMapOf<Pair<TenantId, String>, Pair<String, AcknowledgementLocator>>()

        override fun insert(
            tenant: TenantId,
            eventId: String,
            ackId: String,
            docUuid: UUID,
            version: Int,
            recordedAt: Instant,
        ): Boolean {
            if (rows.containsKey(tenant to ackId) || rows.values.any { it.first == eventId }) return false
            rows[tenant to ackId] = eventId to AcknowledgementLocator(ackId, docUuid, version)
            events += "locator:$eventId"
            return true
        }

        override fun find(
            tenant: TenantId,
            ackId: String,
        ): AcknowledgementLocator? = rows[tenant to ackId]?.second
    }

    private val noLocator =
        object : LocatorStore {
            override fun find(
                tenant: TenantId,
                docUuid: UUID,
                version: Int,
            ): DocumentLocator? = null

            override fun insert(
                tenant: TenantId,
                eventId: String,
                locator: DocumentLocator,
                recordedAt: Instant,
            ): Boolean = error("not used")

            override fun lock(
                tenant: TenantId,
                docUuid: UUID,
                version: Int,
            ) = Unit
        }

    private val pending =
        object : PendingStore {
            override fun insert(
                tenant: TenantId,
                record: PendingRecord,
            ): Boolean {
                kinds += record.kind
                events += "append:${record.eventId}"
                return true
            }

            override fun unapplied(
                tenant: TenantId,
                docUuid: UUID,
                version: Int,
            ): List<PendingRecord> = emptyList()
        }

    private val applied =
        object : AppliedStore {
            override fun isApplied(
                tenant: TenantId,
                eventId: String,
            ) = false

            override fun insert(
                tenant: TenantId,
                record: AppliedRecord,
            ) = Unit
        }

    private val ledger =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(EvidenceLedger::class.java)) { _, _, _ ->
            error("the ledger is not reached while no locator is stored")
        } as EvidenceLedger

    private val locators = Locators()
    private val recorder =
        AcknowledgementRecorder(
            locators,
            RecordAppender(RecordKind.entries.associateWith { ledger }, noLocator, pending, applied),
            Clock.fixed(Instant.parse("2026-01-15T00:00:00Z"), ZoneOffset.UTC),
        )
    private val tenant = TenantId(UUID.randomUUID())
    private val doc = UUID.randomUUID()
    private val content = "{}".toByteArray()

    private fun record(
        eventId: String,
        ackId: String = "A1",
        version: Int = 2,
    ) = recorder.record(tenant, eventId, ackId, doc, version, content)

    @Test
    fun `INV-AC-20570 the first event writes one locator and one ACKNOWLEDGEMENT append`() {
        record("e1") shouldBe true
        events shouldBe listOf("locator:e1", "append:e1")
        kinds shouldBe listOf(RecordKind.ACKNOWLEDGEMENT)
    }

    @Test
    fun `INV-AC-20570 a repeat of the event id writes neither locator nor append`() {
        record("e1")
        record("e1") shouldBe false
        events shouldBe listOf("locator:e1", "append:e1")
    }

    @Test
    fun `INV-AC-20570 an ack id stored for another version throws ContractViolation and writes nothing`() {
        record("e1")
        shouldThrow<ContractViolation> { record("e2", version = 3) }
        events shouldBe listOf("locator:e1", "append:e1")
    }

    @Test
    fun `INV-AC-20570 the same ack id and version in a new event is a no-op`() {
        record("e1")
        record("e2") shouldBe false
        events shouldBe listOf("locator:e1", "append:e1")
    }

    @Test
    fun `INV-AC-20570 the conflict is checked before any write`() {
        record("e1")
        events.clear()
        shouldThrow<ContractViolation> { recorder.record(tenant, "e9", "A1", UUID.randomUUID(), 2, content) }
        events shouldBe emptyList()
        locators.rows.size shouldBe 1
    }
}
