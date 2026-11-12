package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.persistence.ChainWiring
import com.dynokore.gst.evidence.adapters.persistence.JooqAcknowledgementLocatorStore
import com.dynokore.gst.evidence.adapters.persistence.JooqChainRecordReader
import com.dynokore.gst.evidence.adapters.persistence.JooqLocatorStore
import com.dynokore.gst.evidence.adapters.persistence.JooqSealedPayloadSink
import com.dynokore.gst.evidence.application.chain.AcknowledgementRecorder
import com.dynokore.gst.evidence.application.lookup.ChainItemKind
import com.dynokore.gst.evidence.application.lookup.ChainRecords
import com.dynokore.gst.evidence.domain.chain.DocumentLocator
import com.dynokore.gst.evidence.domain.chain.RecordKind
import com.dynokore.platform.evidencestore.JdbcEvidenceLedger
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** INV-AC-20574: the chain records of one version are found through the locator and `applied_record`. */
class ChainRecordReaderDbTest {
    private val doc = UUID.fromString("0b1f2c3d-0000-4000-8000-0000000000a1")
    private val at = Instant.parse("2026-10-20T10:00:00Z")

    private fun <T> tx(
        tenant: UUID,
        body: (DSLContext, TenantId) -> T,
    ): T =
        EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, tenant)
            body(DSL.using(c, SQLDialect.POSTGRES), TenantId(tenant)).also { c.commit() }
        }

    private fun reader(dsl: DSLContext) =
        JooqChainRecordReader(
            dsl,
            RecordKind.entries.associateWith { JdbcEvidenceLedger(dsl, ChainWiring.SCHEMA, it.table) },
        )

    private fun locate(
        tenant: UUID,
        version: Int = 2,
    ) = tx(tenant) { dsl, t ->
        JooqLocatorStore(dsl).insert(
            t,
            "L$version",
            DocumentLocator(doc, version, "ENT-1", "2026-Q1", LocalDate.of(2026, 1, 15), "trace/$version", "INV-1"),
            at,
        )
    }

    private fun seal(
        tenant: UUID,
        version: Int,
        eventId: String,
        appliedAt: Instant = at,
    ) = tx(tenant) { dsl, t ->
        JooqSealedPayloadSink(dsl, ChainWiring.recordAppender(dsl, Clock.fixed(appliedAt, ZoneOffset.UTC)))
            .append(t, doc, version, eventId, "{\"e\":\"$eventId\"}".toByteArray(), at)
    }

    private fun acknowledge(
        tenant: UUID,
        ackId: String,
        version: Int,
        eventId: String,
        appliedAt: Instant = at,
    ) = tx(tenant) { dsl, t ->
        val clock = Clock.fixed(appliedAt, ZoneOffset.UTC)
        AcknowledgementRecorder(JooqAcknowledgementLocatorStore(dsl), ChainWiring.recordAppender(dsl, clock), clock)
            .record(t, eventId, ackId, doc, version, "{\"ack\":\"$ackId\"}".toByteArray())
    }

    private fun read(
        tenant: UUID,
        ackId: String,
        version: Int,
    ) = tx(tenant) { dsl, t -> reader(dsl).read(t, ackId, doc, version) }

    private fun complete(records: ChainRecords) = records.shouldBeInstanceOf<ChainRecords.Complete>().items

    @Test
    fun `INV-AC-20574 the ACKNOWLEDGEMENT record is found through the locator's source event`() {
        val t = UUID.randomUUID()
        locate(t)
        acknowledge(t, "A1", 2, "ACK-E1")
        val items = complete(read(t, "A1", 2))
        items.map { it.kind } shouldBe listOf(ChainItemKind.ACKNOWLEDGEMENT)
        items.single().reference shouldBe "A1"
    }

    @Test
    fun `INV-AC-20574 two sealed payload rows of one version yield two PAYLOAD items in sequence order`() {
        val t = UUID.randomUUID()
        locate(t)
        seal(t, 2, "P1")
        seal(t, 2, "P2")
        acknowledge(t, "A1", 2, "ACK-E1")
        val items = complete(read(t, "A1", 2))
        items.filter { it.kind == ChainItemKind.PAYLOAD }.map { it.reference } shouldBe
            listOf("payload/$doc/v2/0", "payload/$doc/v2/1")
        items.map { it.sequence } shouldBe listOf(1L, 2L, 3L)
    }

    @Test
    fun `INV-AC-20574 a record of another version of the same document is not returned`() {
        val t = UUID.randomUUID()
        locate(t, 1)
        locate(t, 2)
        seal(t, 1, "P-V1")
        seal(t, 2, "P-V2")
        acknowledge(t, "A1", 2, "ACK-E1")
        val items = complete(read(t, "A1", 2))
        items.filter { it.kind == ChainItemKind.PAYLOAD }.map { it.reference } shouldBe listOf("payload/$doc/v2/1")
    }

    @Test
    fun `INV-AC-20574 a second tenant reads nothing`() {
        val t = UUID.randomUUID()
        locate(t)
        seal(t, 2, "P1")
        acknowledge(t, "A1", 2, "ACK-E1")
        read(UUID.randomUUID(), "A1", 2) shouldBe ChainRecords.Incomplete
    }

    @Test
    fun `INV-AC-20574 a missing applied_record row is reported as incomplete, never skipped`() {
        val noAck = UUID.randomUUID()
        locate(noAck)
        seal(noAck, 2, "P1")
        read(noAck, "A9", 2) shouldBe ChainRecords.Incomplete
        // a sealed_payload link whose applied_record row is absent: written straight, as a repair-gap fixture
        val t = UUID.randomUUID()
        locate(t)
        acknowledge(t, "A1", 2, "ACK-E1")
        tx(t) { dsl, tenant ->
            dsl.execute(
                "insert into evidence.sealed_payload (tenant_id, doc_uuid, version, source_event_id, recorded_at) " +
                    "values (?, ?, 2, 'GHOST', now())",
                tenant.value,
                doc,
            )
        }
        read(t, "A1", 2) shouldBe ChainRecords.Incomplete
    }

    private val early = Instant.parse("2026-10-20T10:00:00Z")
    private val late = Instant.parse("2026-10-20T11:00:00Z")

    @Test
    fun `INV-AC-20574 equal raw seq in two chain tables gives ranks 1 and 2 by applied_at and reverses when swapped`() {
        for ((ackAt, payAt, expected) in listOf(
            Triple(late, early, listOf(ChainItemKind.PAYLOAD, ChainItemKind.ACKNOWLEDGEMENT)),
            Triple(early, late, listOf(ChainItemKind.ACKNOWLEDGEMENT, ChainItemKind.PAYLOAD)),
        )) {
            val t = UUID.randomUUID()
            locate(t)
            seal(t, 2, "P1", payAt)
            acknowledge(t, "A1", 2, "ACK-E1", ackAt)
            val items = complete(read(t, "A1", 2))
            items.map { it.kind } shouldBe expected
            items.map { it.sequence } shouldBe listOf(1L, 2L)
        }
    }

    @Test
    fun `INV-AC-20574 equal applied_at orders PAYLOAD rows by raw seq and ranks are at least 1 and unique`() {
        val t = UUID.randomUUID()
        locate(t)
        seal(t, 2, "P1")
        seal(t, 2, "P2")
        acknowledge(t, "A1", 2, "ACK-E1")
        val items = complete(read(t, "A1", 2))
        items.map { it.kind } shouldBe
            listOf(ChainItemKind.PAYLOAD, ChainItemKind.PAYLOAD, ChainItemKind.ACKNOWLEDGEMENT)
        items.map { it.reference } shouldBe listOf("payload/$doc/v2/0", "payload/$doc/v2/1", "A1")
        items.map { it.sequence }.toSet().size shouldBe items.size
        (items.minOf { it.sequence } >= 1L) shouldBe true
    }

    @Test
    fun `INV-AC-20574 the PAYLOAD reference holds the raw seq, not the rank`() {
        val t = UUID.randomUUID()
        locate(t)
        acknowledge(t, "A1", 2, "ACK-E1", early)
        seal(t, 2, "P1", late)
        val payload = complete(read(t, "A1", 2)).single { it.kind == ChainItemKind.PAYLOAD }
        payload.sequence shouldBe 2L
        payload.reference shouldBe "payload/$doc/v2/0"
        (payload.reference == "payload/$doc/v2/2") shouldBe false
    }
}
