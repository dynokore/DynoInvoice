package com.dynokore.gst.evidence.application.chain

import com.dynokore.gst.evidence.domain.chain.ChainKey
import com.dynokore.gst.evidence.domain.chain.DocumentLocator
import com.dynokore.gst.evidence.domain.chain.RecordKind
import com.dynokore.platform.evidencestore.ContentHash
import com.dynokore.platform.evidencestore.EntityRef
import com.dynokore.platform.evidencestore.EvidenceIndex
import com.dynokore.platform.evidencestore.EvidenceLedger
import com.dynokore.platform.evidencestore.EvidenceRecord
import com.dynokore.platform.evidencestore.EvidenceType
import com.dynokore.platform.evidencestore.PeriodRef
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** INV-AC-20748: which kinds pass the locator version to their ledger, with fake ledgers that record the index. */
class RecordAppenderVersionTest {
    private val tenant = TenantId(UUID.randomUUID())
    private val doc = UUID.fromString("0b1f2c3d-0000-4000-8000-0000000000e2")
    private val at = Instant.parse("2026-10-20T10:00:00Z")
    private val locator = DocumentLocator(doc, 3, "ENT-1", "2026-Q1", LocalDate.of(2026, 1, 15), "trace-3", "INV-9")
    private val received = linkedMapOf<RecordKind, MutableList<EvidenceIndex>>()
    private val pendingRows = mutableListOf<PendingRecord>()
    private val appliedIds = mutableSetOf<String>()

    private fun ledger(kind: RecordKind): EvidenceLedger =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(EvidenceLedger::class.java)) { _, method, args ->
            check(method.name.startsWith("append")) { "unexpected ${method.name}" }
            val index = args!![5] as EvidenceIndex
            received.getOrPut(kind) { mutableListOf() } += index
            EvidenceRecord(
                tenantId = TenantId(args[0] as UUID),
                entityRef = EntityRef(args[1] as String),
                periodRef = PeriodRef(args[2] as String),
                evidenceType = args[3] as EvidenceType,
                sequence = 1,
                content = args[4] as ByteArray,
                contentHash = ContentHash("a".repeat(64)),
                previousHash = ContentHash("b".repeat(64)),
                recordedAt = at,
                index = index,
            )
        } as EvidenceLedger

    private val locators =
        object : LocatorStore {
            var stored: DocumentLocator? = locator

            override fun find(
                tenant: TenantId,
                docUuid: UUID,
                version: Int,
            ): DocumentLocator? = stored?.takeIf { it.docUuid == docUuid && it.version == version }

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
            ): Boolean = pendingRows.add(record)

            override fun unapplied(
                tenant: TenantId,
                docUuid: UUID,
                version: Int,
            ): List<PendingRecord> = pendingRows.filter { it.eventId !in appliedIds }
        }

    private val applied =
        object : AppliedStore {
            override fun isApplied(
                tenant: TenantId,
                eventId: String,
            ): Boolean = eventId in appliedIds

            override fun insert(
                tenant: TenantId,
                record: AppliedRecord,
            ) {
                appliedIds.add(record.eventId)
            }
        }

    private fun appender() =
        RecordAppender(
            RecordKind.entries.associateWith { ledger(it) },
            locators,
            pending,
            applied,
        )

    private fun RecordAppender.bound(
        kind: RecordKind,
        eventId: String,
    ) = appendBound(tenant, kind, doc, 3, eventId, "{}".toByteArray(), at)

    @Test
    fun `INV-AC-20748 appendBound of a DECISION_TRACE record passes an index whose version is 3`() {
        appender().bound(RecordKind.DECISION_TRACE, "E1")
        received[RecordKind.DECISION_TRACE] shouldBe listOf(EvidenceIndex(doc, "INV-9", version = 3))
    }

    @Test
    fun `INV-AC-20748 drainPending passes the version of the pending record`() {
        locators.stored = null
        val a = appender()
        a.bound(RecordKind.DECISION_TRACE, "E1")
        received.size shouldBe 0
        locators.stored = locator
        a.drainPending(tenant, doc, 3) shouldBe 1
        received[RecordKind.DECISION_TRACE] shouldBe listOf(EvidenceIndex(doc, "INV-9", version = 3))
    }

    @Test
    fun `INV-AC-20748 every other kind passes version null with the document number and Doc UUID unchanged`() {
        val a = appender()
        RecordKind.entries.filter { it != RecordKind.DECISION_TRACE && it != RecordKind.AUDIT_LOG }.forEach {
            a.bound(it, "E-$it")
        }
        a.appendAudit(tenant, ChainKey("ENT-1", "2026-Q1"), "E-AUDIT", "{}".toByteArray(), at)
        received.keys shouldBe setOf(RecordKind.EVIDENCE_PAYLOAD, RecordKind.ACKNOWLEDGEMENT, RecordKind.AUDIT_LOG)
        received[RecordKind.EVIDENCE_PAYLOAD] shouldBe listOf(EvidenceIndex(doc, "INV-9"))
        received[RecordKind.ACKNOWLEDGEMENT] shouldBe listOf(EvidenceIndex(doc, "INV-9"))
        received[RecordKind.AUDIT_LOG] shouldBe listOf(EvidenceIndex.EMPTY)
        received.values
            .flatten()
            .map { it.version }
            .toSet() shouldBe setOf(null)
    }

    @Test
    fun `INV-AC-20748 a pending record of another kind is drained with version null`() {
        locators.stored = null
        val a = appender()
        a.bound(RecordKind.EVIDENCE_PAYLOAD, "P1")
        locators.stored = locator
        a.drainPending(tenant, doc, 3) shouldBe 1
        received[RecordKind.EVIDENCE_PAYLOAD]!!.single().version shouldBe null
    }
}
