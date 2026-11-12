package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.persistence.ChainWiring
import com.dynokore.gst.evidence.adapters.persistence.JooqSubmissionIndexStore
import com.dynokore.gst.evidence.application.chain.SubmissionIndexRow
import com.dynokore.gst.evidence.domain.chain.DocumentLocator
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
import java.time.ZoneOffset
import java.util.UUID

/** INV-AC-20720: the acknowledgement-time write of `evidence.submission_index`, one row per version. */
class SubmissionIndexAckDbTest {
    private val at = Instant.parse("2026-01-15T00:00:00Z")
    private val t1 = Instant.parse("2026-01-16T10:00:00Z")
    private val t2 = Instant.parse("2026-01-17T10:00:00Z")
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
            JooqSubmissionIndexStore(dsl)
                .upsertDocument(t, SubmissionIndexRow(doc, 1, "ENT-1", "2026-Q1", "INV-1", LocalDate.of(2026, 1, 15), "201912345A", at))
        }

    private fun despatch(tenant: UUID) =
        EvidenceDb.superuser().use { c ->
            c.createStatement().use {
                it.executeUpdate("update evidence.submission_index set state = 'SENT' where tenant_id = '$tenant'")
            }
        }

    private fun ack(
        tenant: UUID,
        ackId: String,
        at: Instant,
        version: Int = 1,
    ) = tx(tenant) { dsl, t -> JooqSubmissionIndexStore(dsl).upsertAcknowledgement(t, doc, version, ackId, at) }

    /** document_number, document_date, ack_id, acknowledged_at, state of every row of the tenant. */
    private fun rows(tenant: UUID): List<List<String?>> =
        EvidenceDb.superuser().use { c ->
            c
                .prepareStatement(
                    "select document_number, document_date::text, ack_id, " +
                        "to_char(acknowledged_at at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"'), state " +
                        "from evidence.submission_index where tenant_id = ? order by version",
                ).use {
                    it.setObject(1, tenant)
                    it.executeQuery().use { rs ->
                        buildList { while (rs.next()) add((1..5).map { i -> rs.getString(i) }) }
                    }
                }
        }

    private fun refused(
        tenant: UUID,
        sql: String,
    ): SQLException =
        EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, tenant)
            val failure = runCatching { c.createStatement().use { it.execute(sql) } }.exceptionOrNull()
            c.rollback()
            failure as SQLException
        }

    @Test
    fun `INV-AC-20720 the first event sets the three values on the existing row and leaves its document columns`() {
        val t = UUID.randomUUID()
        document(t)
        ack(t, "K1", t1) shouldBe true
        rows(t) shouldBe listOf(listOf("INV-1", "2026-01-15", "K1", "2026-01-16T10:00:00Z", "ACKNOWLEDGED"))
    }

    @Test
    fun `INV-AC-20720 a row whose state is SENT gets state ACKNOWLEDGED`() {
        val t = UUID.randomUUID()
        document(t)
        despatch(t)
        rows(t).single()[4] shouldBe "SENT"
        ack(t, "K1", t1) shouldBe true
        rows(t).single()[4] shouldBe "ACKNOWLEDGED"
    }

    @Test
    fun `INV-AC-20720 a row that has no state yet gets ACKNOWLEDGED`() {
        val t = UUID.randomUUID()
        document(t)
        rows(t).single()[4] shouldBe null
        ack(t, "K1", t1)
        rows(t).single()[4] shouldBe "ACKNOWLEDGED"
    }

    @Test
    fun `INV-AC-20720 a repeat changes nothing`() {
        val t = UUID.randomUUID()
        document(t)
        ack(t, "K1", t1)
        val before = rows(t)
        ack(t, "K1", t1) shouldBe false
        rows(t) shouldBe before
    }

    @Test
    fun `INV-AC-20720 a second ack id for the same version changes nothing and does not fail`() {
        val t = UUID.randomUUID()
        document(t)
        ack(t, "K1", t1)
        ack(t, "K2", t2) shouldBe false
        rows(t) shouldBe listOf(listOf("INV-1", "2026-01-15", "K1", "2026-01-16T10:00:00Z", "ACKNOWLEDGED"))
    }

    @Test
    fun `INV-AC-20720 an event for a version with no row creates a partial row with document_date null`() {
        val t = UUID.randomUUID()
        ack(t, "K1", t1, version = 7) shouldBe true
        rows(t) shouldBe listOf(listOf(null, null, "K1", "2026-01-16T10:00:00Z", "ACKNOWLEDGED"))
    }

    @Test
    fun `INV-AC-20720 a second tenant reads nothing`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        ack(a, "K1", t1)
        tx(b) { dsl, _ -> dsl.fetchCount(DSL.table(DSL.name("evidence", "submission_index"))) } shouldBe 0
    }

    @Test
    fun `INV-AC-20720 a DELETE by evidence_runtime is refused`() {
        val t = UUID.randomUUID()
        ack(t, "K1", t1)
        refused(t, "delete from evidence.submission_index").sqlState shouldBe "42501"
    }

    @Test
    fun `INV-AC-20720 a rollback of the recorder transaction leaves neither locator, chain record nor index change`() {
        val t = UUID.randomUUID()
        document(t)
        val clock = Clock.fixed(at, ZoneOffset.UTC)
        tx(t) { dsl, tenant ->
            val locator = DocumentLocator(doc, 1, "ENT-1", "2026-Q1", LocalDate.of(2026, 1, 15), "tr", "INV-1")
            ChainWiring.locatorRecorder(dsl, clock).record(tenant, "e0", locator, "201912345A")
        }
        val before = count(t, "acknowledgement")
        EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, t)
            val dsl = DSL.using(c, SQLDialect.POSTGRES)
            ChainWiring
                .acknowledgementRecorder(dsl, clock)
                .record(TenantId(t), "ev-ack", "K1", doc, 1, "{}".toByteArray(), t1) shouldBe true
            c.rollback()
        }
        rows(t) shouldBe listOf(listOf("INV-1", "2026-01-15", null, null, null))
        count(t, "acknowledgement_locator") shouldBe 0
        count(t, "acknowledgement") shouldBe before
    }

    private fun count(
        tenant: UUID,
        table: String,
    ): Int =
        EvidenceDb.superuser().use { c ->
            c.createStatement().use { st ->
                st.executeQuery("select count(*) from evidence.$table where tenant_id = '$tenant'").use {
                    it.next()
                    it.getInt(1)
                }
            }
        }
}
