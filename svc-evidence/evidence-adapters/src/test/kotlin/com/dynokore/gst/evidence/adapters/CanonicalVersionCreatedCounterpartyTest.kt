package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.events.CanonicalVersionCreatedHandler
import com.dynokore.gst.evidence.application.chain.AppliedRecord
import com.dynokore.gst.evidence.application.chain.AppliedStore
import com.dynokore.gst.evidence.application.chain.LocatorRecorder
import com.dynokore.gst.evidence.application.chain.LocatorStore
import com.dynokore.gst.evidence.application.chain.PendingRecord
import com.dynokore.gst.evidence.application.chain.PendingStore
import com.dynokore.gst.evidence.application.chain.RecordAppender
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

/** INV-AC-20718: the counterparty UEN the handler passes to the recorder, by direction and scheme. */
class CanonicalVersionCreatedCounterpartyTest {
    private val tenant = TenantId(UUID.randomUUID())
    private val doc = UUID.randomUUID()
    private val written = mutableListOf<SubmissionIndexRow>()

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
            ): Boolean = written.add(row)

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
    private val handler =
        CanonicalVersionCreatedHandler(
            LocatorRecorder(
                locators,
                RecordAppender(RecordKind.entries.associateWith { ledger }, locators, pending, applied),
                index,
            ),
        )

    private fun uen(
        direction: String?,
        scheme: String,
    ): String? {
        written.clear()
        val routing = direction?.let { ""","routingFacts":{"direction":"$it"}""" }.orEmpty()
        val data =
            """{"docUuid":"$doc","version":1,"prescribedPeriodRef":"2026-Q1","transactionDate":"2026-01-15",""" +
                """"traceReference":"tr","canonicalDocument":{"documentNumber":{"value":"INV-1"},""" +
                """"parties":{"value":""" +
                """{"supplier":{"identifierScheme":"$scheme","identifierValue":"SUP1"},""" +
                """"buyer":{"identifierScheme":"$scheme","identifierValue":"BUY1"}}}}$routing}"""
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
        return written.single().counterpartyUen
    }

    @Test
    fun `INV-AC-20718 a SALE document passes the buyer UEN`() {
        uen("SALE", "SG_UEN") shouldBe "BUY1"
    }

    @Test
    fun `INV-AC-20718 a PURCHASE document passes the supplier UEN`() {
        uen("PURCHASE", "SG_UEN") shouldBe "SUP1"
    }

    @Test
    fun `INV-AC-20718 a scheme other than SG_UEN passes null and the look-alike UEN passes null`() {
        uen("SALE", "OTHER") shouldBe null
        uen("SALE", "UEN") shouldBe null
        uen("SALE", "sg_uen") shouldBe null
    }

    @Test
    fun `INV-AC-20718 absent routingFacts passes null and the row is still written`() {
        uen(null, "SG_UEN") shouldBe null
        written.single().documentNumber shouldBe "INV-1"
    }
}
