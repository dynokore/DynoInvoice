package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.events.LoggingSealSignals
import com.dynokore.gst.evidence.adapters.events.PayloadReadyHandler
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
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.slf4j.Logger
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import com.dynokore.platform.outboxandqueue.EntityRef as EventEntityRef

/** INV-AC-20568: the two handlers over the sealer with in-memory fakes of the five ports. */
class SealingHandlersTest {
    private class Held : HeldPayloadStore {
        val rows = mutableMapOf<Pair<UUID, Int>, HeldPayload>()

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
        ): Boolean = rows.putIfAbsent(held.docUuid to held.version, held) == null

        override fun find(
            tenant: TenantId,
            docUuid: UUID,
            version: Int,
        ): HeldPayload? = rows[docUuid to version]

        override fun remove(
            tenant: TenantId,
            docUuid: UUID,
            version: Int,
        ) {
            rows.remove(docUuid to version)
        }
    }

    private class Parked : ParkedDespatchStore {
        val rows = mutableListOf<ParkedDespatch>()

        override fun park(
            tenant: TenantId,
            parked: ParkedDespatch,
        ): Boolean = rows.none { it.eventId == parked.eventId } && rows.add(parked)

        override fun parkedFor(
            tenant: TenantId,
            docUuid: UUID,
            version: Int,
        ): List<ParkedDespatch> = rows.filter { it.despatch.docUuid == docUuid && it.despatch.version == version }
    }

    private class Quarantine : PayloadQuarantineStore {
        val rows = mutableListOf<String>()

        override fun quarantine(
            tenant: TenantId,
            causedByEventId: String,
            held: HeldPayload,
            origin: QuarantineOrigin,
            recordedAt: Instant,
        ) {
            if (!rows.contains(causedByEventId)) rows += causedByEventId
        }
    }

    /** Idempotent by event id, as the real sink: a repeat of an event id appends nothing. */
    private class Sink : SealedPayloadSink {
        val sealed = linkedMapOf<String, ByteArray>()

        override fun append(
            tenant: TenantId,
            docUuid: UUID,
            version: Int,
            eventId: String,
            content: ByteArray,
            arrivedAt: Instant,
        ) {
            sealed.putIfAbsent(eventId, content)
        }
    }

    private val lines = mutableListOf<String>()
    private val logger: Logger =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Logger::class.java)) { _, method, args ->
            if (method.name == "warn" || method.name == "info" || method.name == "error") {
                lines += args.orEmpty().joinToString(" ") { if (it is Array<*>) it.joinToString(" ") else "$it" }
            }
            if (method.returnType == Boolean::class.javaPrimitiveType) true else null
        } as Logger

    private val held = Held()
    private val parked = Parked()
    private val quarantine = Quarantine()
    private val sink = Sink()
    private val clock = Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC)
    private val sealer = TransmittedPayloadSealer(held, parked, quarantine, sink, LoggingSealSignals(logger), clock)
    private val ready = PayloadReadyHandler(sealer)
    private val despatched =
        SubmissionDespatchedHandler(
            sealer,
            object : SubmissionIndexStore {
                override fun upsertDocument(
                    tenant: TenantId,
                    row: SubmissionIndexRow,
                ) = false

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
            },
        )

    private val tenant = TenantId(UUID.randomUUID())
    private val doc = UUID.randomUUID()
    private val payload = "<Invoice>SECRET-PAYLOAD-7731</Invoice>"
    private val digest = sha(payload)
    private val despatchTime = Instant.parse("2026-10-30T01:02:03Z")

    private fun sha(text: String) =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))

    private fun b64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray())

    private fun envelope(
        id: String,
        type: String,
        data: String,
        time: Instant = despatchTime,
    ) = Envelope(
        id = EventId(id),
        source = "/gst/test",
        type = type,
        subject = "s",
        time = time,
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

    private fun p(
        bytes: String = payload,
        sha: String = digest,
        docUuid: String = """"$doc"""",
    ) = envelope(
        "P-1",
        "com.dynokore.gst.payload.PayloadReady.v1",
        """{"docUuid":$docUuid,"version":1,"payloadBytes":"${b64(bytes)}","payloadSha256":"$sha"}""",
    )

    private fun s(time: Instant = despatchTime) =
        envelope(
            "S-1",
            "com.dynokore.gst.submission.SubmissionDespatched.v1",
            """{"docUuid":"$doc","version":1,"attemptNumber":1,"payloadSha256":"$digest"}""",
            time,
        )

    @Test
    fun `INV-AC-20568 S then P seals once`() {
        despatched.handle(s())
        sink.sealed.size shouldBe 0
        ready.handle(p())
        sink.sealed.keys.toList() shouldBe listOf("S-1")
    }

    @Test
    fun `INV-AC-20568 P then S seals once`() {
        ready.handle(p())
        sink.sealed.size shouldBe 0
        despatched.handle(s())
        sink.sealed.keys.toList() shouldBe listOf("S-1")
    }

    @Test
    fun `INV-AC-20568 each event delivered twice seals once`() {
        despatched.handle(s())
        despatched.handle(s())
        ready.handle(p())
        ready.handle(p())
        despatched.handle(s())
        sink.sealed.keys.toList() shouldBe listOf("S-1")
        held.rows.size shouldBe 1
        parked.rows.size shouldBe 1
    }

    @Test
    fun `INV-AC-20568 a PayloadReady whose bytes do not match the digest quarantines and seals nothing`() {
        ready.handle(p(sha = sha("another")))
        despatched.handle(s())
        quarantine.rows shouldBe listOf("P-1")
        sink.sealed.size shouldBe 0
        held.rows.size shouldBe 0
    }

    @Test
    fun `INV-AC-20568 a payload lacking docUuid is a ContractViolation that writes nothing`() {
        val noDoc = p().let { it.copy(dataJson = it.dataJson.replace(""""docUuid":"$doc",""", "")) }
        shouldThrow<ContractViolation> { ready.handle(noDoc) }
        val noDocS = s().let { it.copy(dataJson = it.dataJson.replace(""""docUuid":"$doc",""", "")) }
        shouldThrow<ContractViolation> { despatched.handle(noDocS) }
        held.rows.size shouldBe 0
        parked.rows.size shouldBe 0
        quarantine.rows.size shouldBe 0
        sink.sealed.size shouldBe 0
    }

    @Test
    fun `INV-AC-20568 no log line carries payload bytes`() {
        ready.handle(p(sha = sha("another")))
        lines.size shouldBe 1
        lines.forEach {
            it shouldNotContain "SECRET-PAYLOAD-7731"
            it shouldNotContain Base64.getEncoder().encodeToString(payload.toByteArray())
        }
        lines[0].contains("P-1") shouldBe true
    }

    @Test
    fun `INV-AC-20568 despatchedAt is the envelope time and a redelivery seals identical bytes`() {
        ready.handle(p())
        despatched.handle(s())
        val first = sink.sealed.getValue("S-1").toString(Charsets.UTF_8)
        first.contains(""""despatchedAt":"2026-10-30T01:02:03Z"""") shouldBe true
        first shouldNotContain "2026-11-01"
        despatched.handle(s())
        sink.sealed.getValue("S-1").toString(Charsets.UTF_8) shouldBe first
        sink.sealed.size shouldBe 1
    }
}
