package com.dynokore.gst.evidence.application.chain

import com.dynokore.gst.evidence.domain.chain.DocumentLocator
import com.dynokore.gst.evidence.domain.chain.RecordKind
import com.dynokore.platform.evidencestore.EvidenceLedger
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** INV-AC-20718: the index row is written only after a stored locator and before the pending records are drained. */
class LocatorRecorderIndexTest {
    private val events = mutableListOf<String>()
    private var inserts = true
    private var stored: DocumentLocator? = null

    private val locators =
        object : LocatorStore {
            override fun find(
                tenant: TenantId,
                docUuid: UUID,
                version: Int,
            ): DocumentLocator? = stored

            override fun insert(
                tenant: TenantId,
                eventId: String,
                locator: DocumentLocator,
                recordedAt: Instant,
            ): Boolean {
                events += "locator"
                if (inserts) stored = locator
                return inserts
            }

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
            ) = true

            override fun unapplied(
                tenant: TenantId,
                docUuid: UUID,
                version: Int,
            ): List<PendingRecord> = emptyList<PendingRecord>().also { events += "drain" }
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
            error("not reached")
        } as EvidenceLedger
    private val rows = mutableListOf<SubmissionIndexRow>()
    private val index =
        object : SubmissionIndexStore {
            override fun upsertDocument(
                tenant: TenantId,
                row: SubmissionIndexRow,
            ): Boolean {
                events += "index"
                return rows.add(row)
            }

            override fun markDespatched(
                tenant: TenantId,
                docUuid: UUID,
                version: Int,
            ): Boolean = false

            override fun upsertAcknowledgement(
                tenant: TenantId,
                docUuid: UUID,
                version: Int,
                ackId: String,
                acknowledgedAt: Instant,
            ): Boolean = error("not used")
        }
    private val recorder =
        LocatorRecorder(
            locators,
            RecordAppender(RecordKind.entries.associateWith { ledger }, locators, pending, applied),
            index,
            object : SubmissionIndexCategoryStore {
                override fun insertAll(
                    tenant: TenantId,
                    docUuid: UUID,
                    version: Int,
                    codes: Set<String>,
                ) = Unit
            },
        )
    private val locator =
        DocumentLocator(UUID.randomUUID(), 1, "ENT-1", "2026-Q1", LocalDate.of(2026, 1, 15), "tr", "INV-1")

    @Test
    fun `INV-AC-20718 the index row is written after the locator and before the drain, with the counterparty`() {
        recorder.record(TenantId(UUID.randomUUID()), "e1", locator, "U1") shouldBe true
        events.take(3) shouldBe listOf("locator", "index", "drain")
        rows.single().counterpartyUen shouldBe "U1"
        rows.single().documentDate shouldBe LocalDate.of(2026, 1, 15)
    }

    @Test
    fun `INV-AC-20718 no index row is written when the locator insert returned false`() {
        inserts = false
        recorder.record(TenantId(UUID.randomUUID()), "e1", locator) shouldBe false
        rows shouldBe emptyList()
        events shouldBe listOf("locator")
    }
}
