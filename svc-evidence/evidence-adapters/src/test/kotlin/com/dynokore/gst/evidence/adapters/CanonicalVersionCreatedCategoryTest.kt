package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.events.CanonicalVersionCreatedHandler
import com.dynokore.gst.evidence.application.chain.AppliedRecord
import com.dynokore.gst.evidence.application.chain.AppliedStore
import com.dynokore.gst.evidence.application.chain.LocatorRecorder
import com.dynokore.gst.evidence.application.chain.LocatorStore
import com.dynokore.gst.evidence.application.chain.PendingRecord
import com.dynokore.gst.evidence.application.chain.PendingStore
import com.dynokore.gst.evidence.application.chain.RecordAppender
import com.dynokore.gst.evidence.application.chain.SubmissionIndexCategoryStore
import com.dynokore.gst.evidence.application.chain.SubmissionIndexRow
import com.dynokore.gst.evidence.application.chain.SubmissionIndexStore
import com.dynokore.gst.evidence.domain.chain.DocumentLocator
import com.dynokore.gst.evidence.domain.chain.RecordKind
import com.dynokore.platform.evidencestore.EvidenceLedger
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import com.dynokore.platform.outboxandqueue.EntityRef as EventEntityRef

/** INV-AC-20767: the category codes the handler passes to the recorder, from the line tax codes. */
class CanonicalVersionCreatedCategoryTest {
    private val tenant = TenantId(UUID.randomUUID())
    private val doc = UUID.randomUUID()
    private val passed = mutableListOf<Set<String>>()

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
                stored = locator
                return true
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
            error("not reached")
        } as EvidenceLedger
    private val index =
        object : SubmissionIndexStore {
            override fun upsertDocument(
                tenant: TenantId,
                row: SubmissionIndexRow,
            ) = true

            override fun markDespatched(
                tenant: TenantId,
                docUuid: UUID,
                version: Int,
            ) = false

            override fun upsertAcknowledgement(
                tenant: TenantId,
                docUuid: UUID,
                version: Int,
                ackId: String,
                acknowledgedAt: Instant,
            ): Boolean = false
        }
    private val categories =
        object : SubmissionIndexCategoryStore {
            override fun insertAll(
                tenant: TenantId,
                docUuid: UUID,
                version: Int,
                codes: Set<String>,
            ) {
                passed += codes
            }
        }
    private val handler =
        CanonicalVersionCreatedHandler(
            LocatorRecorder(
                locators,
                RecordAppender(RecordKind.entries.associateWith { ledger }, locators, pending, applied),
                index,
                categories,
            ),
        )

    /** Runs one event whose document JSON members after `documentNumber` are [rest]; returns what was passed. */
    private fun run(rest: String): List<String>? {
        passed.clear()
        stored = null
        val data =
            """{"docUuid":"$doc","version":1,"prescribedPeriodRef":"2026-Q1","transactionDate":"2026-01-15",""" +
                """"traceReference":"tr","canonicalDocument":{"documentNumber":{"value":"INV-1"}$rest}}"""
        handler.handle(
            Envelope(
                id = EventId("ev-1"),
                source = "/gst/canonical-svc",
                type = "com.dynokore.gst.canonical.CanonicalVersionCreated.v1",
                subject = "s",
                time = Instant.parse("2026-01-15T00:00:00Z"),
                tenant = tenant,
                entity = EventEntityRef("ENT-1"),
                docUuid = DocUuid(doc),
                transactionDate = LocalDate.of(2026, 1, 15),
                docSequence = 1,
                instanceId = "i-0",
                correlation = CorrelationId("c"),
                causation = CausationId("c"),
                dataJson = data,
            ),
        )
        return passed.singleOrNull()?.toList()
    }

    private fun lines(vararg items: String) = ""","lines":{"value":[${items.joinToString(",")}]}"""

    @Test
    fun `INV-AC-20767 event A passes SR and ZR once each in first-seen order`() {
        run(
            lines(
                """{"taxCode":"SR"}""",
                """{"taxCode":"SR"}""",
                """{"taxCode":"ZR"}""",
                """{"taxCode":"  "}""",
                """{"description":"no code"}""",
            ),
        ) shouldBe listOf("SR", "ZR")
    }

    @Test
    fun `INV-AC-20767 event B with only lines without a taxCode passes an empty set`() {
        run(lines("""{"description":"a"}""", """{"taxCode":7}""", """{"taxCode":null}""")) shouldBe null
    }

    @Test
    fun `INV-AC-20767 event C passes sr and SR as they are`() {
        run(lines("""{"taxCode":"sr"}""", """{"taxCode":"SR"}""")) shouldBe listOf("sr", "SR")
    }

    @Test
    fun `INV-AC-20767 a taxSubtotals code that is on no line is not passed`() {
        run(lines("""{"taxCode":"SR"}""") + ""","taxSubtotals":{"value":[{"taxCode":"ES"}]}""") shouldBe listOf("SR")
    }

    @Test
    fun `INV-AC-20767 a payload with no lines passes an empty set and is not refused`() {
        run("") shouldBe null
        passed shouldBe emptyList()
    }
}
