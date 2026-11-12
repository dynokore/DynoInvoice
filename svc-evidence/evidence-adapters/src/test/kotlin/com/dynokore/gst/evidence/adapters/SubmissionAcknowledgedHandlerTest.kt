package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.events.SubmissionAcknowledgedHandler
import com.dynokore.gst.evidence.application.chain.AcknowledgementLocator
import com.dynokore.gst.evidence.application.chain.AcknowledgementLocatorStore
import com.dynokore.gst.evidence.application.chain.AcknowledgementRecorder
import com.dynokore.gst.evidence.application.chain.AppliedRecord
import com.dynokore.gst.evidence.application.chain.AppliedStore
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
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import com.dynokore.platform.outboxandqueue.EntityRef as EventEntityRef

/** INV-AC-20570: the handler reads docUuid, version and acknowledgementId; a malformed member writes nothing. */
class SubmissionAcknowledgedHandlerTest {
    private class Store : AcknowledgementLocatorStore {
        val inserts = mutableListOf<List<Any>>()

        override fun insert(
            tenant: TenantId,
            eventId: String,
            ackId: String,
            docUuid: UUID,
            version: Int,
            recordedAt: Instant,
        ): Boolean = inserts.add(listOf(tenant, eventId, ackId, docUuid, version))

        override fun find(
            tenant: TenantId,
            ackId: String,
        ): AcknowledgementLocator? = null
    }

    private val acks = mutableListOf<List<Any>>()

    private val index =
        object : SubmissionIndexStore {
            override fun upsertDocument(
                tenant: TenantId,
                row: SubmissionIndexRow,
            ): Boolean = error("not used")

            override fun upsertAcknowledgement(
                tenant: TenantId,
                docUuid: UUID,
                version: Int,
                ackId: String,
                acknowledgedAt: Instant,
            ): Boolean = acks.add(listOf(ackId, acknowledgedAt))
        }

    private val store = Store()
    private val tenant = TenantId(UUID.randomUUID())
    private val doc = UUID.randomUUID()

    private fun handler(): SubmissionAcknowledgedHandler {
        val ledger =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(EvidenceLedger::class.java)) { _, _, _ ->
                error("the ledger is not reached while no locator is stored")
            } as EvidenceLedger
        val locators =
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
                ) = error("not used")

                override fun lock(
                    tenant: TenantId,
                    docUuid: UUID,
                    version: Int,
                ) = Unit
            }
        val pending =
            object : PendingStore {
                override fun insert(
                    tenant: TenantId,
                    record: PendingRecord,
                ) = true

                override fun unapplied(
                    tenant: TenantId,
                    docUuid: UUID,
                    version: Int,
                ) = emptyList<PendingRecord>()
            }
        val applied =
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
        val appender = RecordAppender(RecordKind.entries.associateWith { ledger }, locators, pending, applied)
        return SubmissionAcknowledgedHandler(AcknowledgementRecorder(store, appender, index))
    }

    private fun envelope(data: String) =
        Envelope(
            id = EventId("ev-1"),
            source = "/gst/submission-svc",
            type = "com.dynokore.gst.submission.SubmissionAcknowledged.v1",
            subject = "s",
            time = Instant.parse("2026-01-15T00:00:00Z"),
            tenant = tenant,
            entity = EventEntityRef("ENT-3F2A9C"),
            docUuid = DocUuid(doc),
            transactionDate = LocalDate.of(2026, 1, 15),
            docSequence = 1,
            instanceId = "submission-svc-0",
            correlation = CorrelationId("corr-1"),
            causation = CausationId("cause-1"),
            dataJson = data,
        )

    private fun data(
        ack: String = """"A1"""",
        docUuid: String = """"$doc"""",
        version: String = "2",
    ) = """{"docUuid":$docUuid,"version":$version,"acknowledgementId":$ack,"timestamp":"2026-01-15T00:00:00Z"}"""

    private fun refused(data: String) {
        shouldThrow<ContractViolation> { handler().handle(envelope(data)) }
        store.inserts shouldBe emptyList()
        acks shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20570 a valid payload reaches the recorder with the envelope id`() {
        handler().handle(envelope(data())) shouldBe HandleOutcome.APPLIED
        store.inserts shouldBe listOf(listOf(tenant, "ev-1", "A1", doc, 2))
    }

    @Test
    fun `INV-AC-20570 a missing or blank acknowledgementId is a ContractViolation that writes nothing`() {
        refused("""{"docUuid":"$doc","version":2}""")
        refused(data(ack = """"  """"))
        refused(data(ack = "null"))
        refused(data(ack = "7"))
    }

    @Test
    fun `INV-AC-20570 a non UUID docUuid is a ContractViolation that writes nothing`() {
        refused(data(docUuid = """"not-a-uuid""""))
        refused("""{"version":2,"acknowledgementId":"A1"}""")
        refused("not json")
    }

    @Test
    fun `INV-AC-20570 a version of 0 or a non integer version is refused`() {
        refused(data(version = "0"))
        refused(data(version = "-1"))
        refused(data(version = "1.5"))
        refused(data(version = """"2""""))
    }

    @Test
    fun `INV-AC-20720 the timestamp of the payload reaches the index`() {
        handler().handle(envelope(data())) shouldBe HandleOutcome.APPLIED
        acks shouldBe listOf(listOf("A1", Instant.parse("2026-01-15T00:00:00Z")))
    }

    @Test
    fun `INV-AC-20720 a payload without timestamp is a ContractViolation and writes nothing`() {
        refused("""{"docUuid":"$doc","version":2,"acknowledgementId":"A1"}""")
    }

    @Test
    fun `INV-AC-20720 the look-alike member acknowledgedAt in place of timestamp is refused`() {
        refused("""{"docUuid":"$doc","version":2,"acknowledgementId":"A1","acknowledgedAt":"2026-01-15T00:00:00Z"}""")
    }

    @Test
    fun `INV-AC-20720 a timestamp that is not a date-time is refused`() {
        refused("""{"docUuid":"$doc","version":2,"acknowledgementId":"A1","timestamp":"yesterday"}""")
    }
}
