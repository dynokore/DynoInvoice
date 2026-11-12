package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.events.LoggingSealSignals
import com.dynokore.gst.evidence.adapters.events.SubmissionDespatchedHandler
import com.dynokore.gst.evidence.application.chain.SubmissionIndexRow
import com.dynokore.gst.evidence.application.chain.SubmissionIndexStore
import com.dynokore.gst.evidence.application.payload.HeldPayloadStore
import com.dynokore.gst.evidence.application.payload.ParkedDespatch
import com.dynokore.gst.evidence.application.payload.ParkedDespatchStore
import com.dynokore.gst.evidence.application.payload.PayloadQuarantineStore
import com.dynokore.gst.evidence.application.payload.QuarantineOrigin
import com.dynokore.gst.evidence.application.payload.SealedPayloadSink
import com.dynokore.gst.evidence.application.payload.TransmittedPayloadSealer
import com.dynokore.gst.evidence.domain.payload.HeldPayload
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import com.dynokore.platform.outboxandqueue.EntityRef as EventEntityRef

/** INV-AC-20798: the despatch handler calls `markDespatched` once per event, over fakes of the ports. */
class SubmissionDespatchedIndexTest {
    private class Index : SubmissionIndexStore {
        val calls = mutableListOf<Triple<TenantId, UUID, Int>>()

        override fun upsertDocument(
            tenant: TenantId,
            row: SubmissionIndexRow,
        ) = false

        override fun markDespatched(
            tenant: TenantId,
            docUuid: UUID,
            version: Int,
        ): Boolean = calls.add(Triple(tenant, docUuid, version))

        override fun upsertAcknowledgement(
            tenant: TenantId,
            docUuid: UUID,
            version: Int,
            ackId: String,
            acknowledgedAt: Instant,
        ): Boolean = false
    }

    private class Parked : ParkedDespatchStore {
        val rows = mutableListOf<ParkedDespatch>()

        override fun park(
            tenant: TenantId,
            parked: ParkedDespatch,
        ): Boolean = rows.add(parked)

        override fun parkedFor(
            tenant: TenantId,
            docUuid: UUID,
            version: Int,
        ): List<ParkedDespatch> = rows
    }

    private val held =
        object : HeldPayloadStore {
            override fun lock(
                tenant: TenantId,
                docUuid: UUID,
                version: Int,
            ) = Unit

            override fun insert(
                tenant: TenantId,
                eventId: String,
                held: HeldPayload,
                recordedAt: Instant,
            ) = true

            override fun find(
                tenant: TenantId,
                docUuid: UUID,
                version: Int,
            ): HeldPayload? = null

            override fun remove(
                tenant: TenantId,
                docUuid: UUID,
                version: Int,
            ) = Unit
        }
    private val quarantine =
        object : PayloadQuarantineStore {
            override fun quarantine(
                tenant: TenantId,
                causedByEventId: String,
                held: HeldPayload,
                origin: QuarantineOrigin,
                recordedAt: Instant,
            ) = Unit
        }
    private val sink =
        object : SealedPayloadSink {
            override fun append(
                tenant: TenantId,
                docUuid: UUID,
                version: Int,
                eventId: String,
                content: ByteArray,
                arrivedAt: Instant,
            ) = Unit
        }
    private val parked = Parked()
    private val index = Index()
    private val clock = Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC)
    private val handler =
        SubmissionDespatchedHandler(
            TransmittedPayloadSealer(held, parked, quarantine, sink, LoggingSealSignals(), clock),
            index,
        )
    private val tenant = TenantId(UUID.randomUUID())
    private val doc = UUID.randomUUID()

    private fun envelope(data: String) =
        Envelope(
            id = EventId("S-1"),
            source = "/gst/test",
            type = "com.dynokore.gst.submission.SubmissionDespatched.v1",
            subject = "s",
            time = Instant.parse("2026-10-30T01:02:03Z"),
            tenant = tenant,
            entity = EventEntityRef("ENT-3F2A9C"),
            docUuid = DocUuid(doc),
            transactionDate = LocalDate.of(2026, 1, 15),
            docSequence = 1,
            instanceId = "i-0",
            correlation = CorrelationId("corr-1"),
            causation = CausationId("cause-1"),
            dataJson = data,
        )

    private fun payload(
        uuidMember: String = "docUuid",
        withUuid: Boolean = true,
        withVersion: Boolean = true,
    ): String {
        val parts = mutableListOf<String>()
        if (withUuid) parts += """"$uuidMember":"$doc""""
        if (withVersion) parts += """"version":1"""
        parts += """"attemptNumber":1"""
        parts += """"payloadSha256":"${"a".repeat(64)}""""
        return parts.joinToString(",", "{", "}")
    }

    @Test
    fun `INV-AC-20798 the handler calls markDespatched once with the tenant docUuid and version of the event`() {
        handler.handle(envelope(payload()))
        index.calls shouldBe listOf(Triple(tenant, doc, 1))
    }

    @Test
    fun `INV-AC-20798 a payload without docUuid or without version is a ContractViolation and writes nothing`() {
        shouldThrow<ContractViolation> { handler.handle(envelope(payload(withUuid = false))) }
        shouldThrow<ContractViolation> { handler.handle(envelope(payload(withVersion = false))) }
        index.calls shouldBe emptyList()
        parked.rows shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20798 the look-alike member documentUuid in place of docUuid is refused`() {
        shouldThrow<ContractViolation> { handler.handle(envelope(payload(uuidMember = "documentUuid"))) }
        index.calls shouldBe emptyList()
    }
}
