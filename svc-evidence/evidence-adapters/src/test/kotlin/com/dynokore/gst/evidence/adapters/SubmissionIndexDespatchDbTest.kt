package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.events.LoggingSealSignals
import com.dynokore.gst.evidence.adapters.events.SubmissionDespatchedHandler
import com.dynokore.gst.evidence.adapters.persistence.ChainWiring
import com.dynokore.gst.evidence.adapters.persistence.JooqHeldPayloadStore
import com.dynokore.gst.evidence.adapters.persistence.JooqParkedDespatchStore
import com.dynokore.gst.evidence.adapters.persistence.JooqPayloadQuarantineStore
import com.dynokore.gst.evidence.adapters.persistence.JooqSealedPayloadSink
import com.dynokore.gst.evidence.adapters.persistence.JooqSubmissionIndexStore
import com.dynokore.gst.evidence.application.chain.SubmissionIndexRow
import com.dynokore.gst.evidence.application.payload.TransmittedPayloadSealer
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import com.dynokore.platform.outboxandqueue.EntityRef as EventEntityRef

/** INV-AC-20798: the despatch-time write of state SENT on `evidence.submission_index`. */
class SubmissionIndexDespatchDbTest {
    private val at = Instant.parse("2026-01-15T00:00:00Z")
    private val doc = UUID.randomUUID()

    private fun <T> tx(
        tenant: UUID,
        block: (DSLContext, TenantId) -> T,
    ): T =
        EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, tenant)
            block(DSL.using(c, SQLDialect.POSTGRES), TenantId(tenant)).also { c.commit() }
        }

    private fun document(tenant: UUID) =
        tx(tenant) { dsl, t ->
            JooqSubmissionIndexStore(dsl).upsertDocument(
                t,
                SubmissionIndexRow(doc, 1, "ENT-1", "2026-Q1", "INV-1", LocalDate.of(2026, 1, 15), "201912345A", at),
            )
        }

    private fun mark(
        tenant: UUID,
        version: Int = 1,
    ) = tx(tenant) { dsl, t -> JooqSubmissionIndexStore(dsl).markDespatched(t, doc, version) }

    private fun rows(tenant: UUID): List<List<String?>> =
        EvidenceDb.superuser().use { c ->
            c
                .prepareStatement(
                    "select version::text, document_number, document_date::text, period_ref, entity_ref, " +
                        "counterparty_uen, ack_id, state from evidence.submission_index where tenant_id = ? " +
                        "order by version",
                ).use {
                    it.setObject(1, tenant)
                    it.executeQuery().use { rs ->
                        buildList { while (rs.next()) add((1..8).map { i -> rs.getString(i) }) }
                    }
                }
        }

    private fun count(
        table: String,
        tenant: UUID,
    ): Int =
        EvidenceDb.superuser().use { c ->
            c.createStatement().use {
                it.executeQuery("select count(*) from evidence.$table where tenant_id = '$tenant'").use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private val withDocument = listOf("1", "INV-1", "2026-01-15", "2026-Q1", "ENT-1", "201912345A", null)

    @Test
    fun `INV-AC-20798 the first event sets SENT on the existing row and changes no other column`() {
        val t = UUID.randomUUID()
        document(t)
        mark(t) shouldBe true
        rows(t) shouldBe listOf(withDocument + "SENT")
    }

    @Test
    fun `INV-AC-20798 a repeat and a second attempt change nothing`() {
        val t = UUID.randomUUID()
        document(t)
        mark(t) shouldBe true
        mark(t) shouldBe false
        mark(t) shouldBe false
        rows(t) shouldBe listOf(withDocument + "SENT")
    }

    @Test
    fun `INV-AC-20798 a row whose state is ACKNOWLEDGED keeps ACKNOWLEDGED`() {
        val t = UUID.randomUUID()
        EvidenceDb.superuser().use { c ->
            c.createStatement().use {
                it.execute(
                    "insert into evidence.submission_index (tenant_id, doc_uuid, version, ack_id, acknowledged_at, " +
                        "state, recorded_at) values ('$t', '$doc', 1, 'A1', '2026-01-16T00:00:00Z', " +
                        "'ACKNOWLEDGED', now())",
                )
            }
        }
        mark(t) shouldBe false
        rows(t).single().last() shouldBe "ACKNOWLEDGED"
    }

    @Test
    fun `INV-AC-20798 an event for a version with no row creates a row with only the key and SENT`() {
        val t = UUID.randomUUID()
        mark(t, 2) shouldBe true
        rows(t) shouldBe listOf(listOf("2", null, null, null, null, null, null, "SENT"))
    }

    @Test
    fun `INV-AC-20798 a second tenant reads nothing`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        mark(a)
        tx(b) { dsl, _ -> dsl.fetchCount(DSL.table(DSL.name("evidence", "submission_index"))) } shouldBe 0
    }

    @Test
    fun `INV-AC-20798 DELETE by evidence_runtime is refused`() {
        val t = UUID.randomUUID()
        mark(t)
        EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, t)
            val failure =
                runCatching { c.createStatement().use { it.execute("delete from evidence.submission_index") } }
                    .exceptionOrNull()
            c.rollback()
            (failure as SQLException).sqlState shouldBe "42501"
        }
    }

    @Test
    fun `INV-AC-20798 a rollback of the handler transaction leaves neither the sealing work nor the state`() {
        val t = UUID.randomUUID()
        val clock = Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), java.time.ZoneOffset.UTC)
        EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, t)
            val dsl = DSL.using(c, SQLDialect.POSTGRES)
            val sealer =
                TransmittedPayloadSealer(
                    JooqHeldPayloadStore(dsl),
                    JooqParkedDespatchStore(dsl),
                    JooqPayloadQuarantineStore(dsl),
                    JooqSealedPayloadSink(dsl, ChainWiring.recordAppender(dsl)),
                    LoggingSealSignals(),
                    clock,
                )
            val handler = SubmissionDespatchedHandler(sealer, JooqSubmissionIndexStore(dsl))
            handler.handle(despatchEnvelope(t))
            dsl.fetchCount(DSL.table(DSL.name("evidence", "submission_index"))) shouldBe 1
            dsl.fetchCount(DSL.table(DSL.name("evidence", "parked_despatch"))) shouldBe 1
            c.rollback()
        }
        rows(t) shouldBe emptyList()
        count("parked_despatch", t) shouldBe 0
    }

    private fun despatchEnvelope(t: UUID) =
        Envelope(
            id = EventId("S-RB"),
            source = "/gst/test",
            type = "com.dynokore.gst.submission.SubmissionDespatched.v1",
            subject = "s",
            time = at,
            tenant = TenantId(t),
            entity = EventEntityRef("ENT-3F2A9C"),
            docUuid = DocUuid(doc),
            transactionDate = LocalDate.of(2026, 1, 15),
            docSequence = 1,
            instanceId = "i-0",
            correlation = CorrelationId("corr-1"),
            causation = CausationId("cause-1"),
            dataJson = """{"docUuid":"$doc","version":1,"attemptNumber":1,"payloadSha256":"${"a".repeat(64)}"}""",
        )
}
