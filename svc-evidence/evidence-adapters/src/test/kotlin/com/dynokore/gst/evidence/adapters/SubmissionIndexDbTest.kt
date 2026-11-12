package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.persistence.ChainWiring
import com.dynokore.gst.evidence.adapters.persistence.JooqSubmissionIndexStore
import com.dynokore.gst.evidence.application.chain.SubmissionIndexRow
import com.dynokore.gst.evidence.domain.chain.DocumentLocator
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** INV-AC-20718: the document write of `evidence.submission_index`, one row per version. */
class SubmissionIndexDbTest {
    private val at = Instant.parse("2026-01-15T00:00:00Z")
    private val doc = UUID.randomUUID()

    private fun row(
        number: String? = "INV-1",
        date: LocalDate = LocalDate.of(2026, 1, 15),
        uen: String? = "201912345A",
        period: String = "2026-Q1",
    ) = SubmissionIndexRow(doc, 1, "ENT-1", period, number, date, uen, at)

    private fun <T> tx(
        tenant: UUID,
        block: (org.jooq.DSLContext, TenantId) -> T,
    ): T =
        EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, tenant)
            block(DSL.using(c, SQLDialect.POSTGRES), TenantId(tenant)).also { c.commit() }
        }

    private fun upsert(
        tenant: UUID,
        r: SubmissionIndexRow,
    ) = tx(tenant) { dsl, t -> JooqSubmissionIndexStore(dsl).upsertDocument(t, r) }

    private fun rows(tenant: UUID): List<List<String?>> =
        EvidenceDb.superuser().use { c ->
            c
                .prepareStatement(
                    "select document_number, document_date::text, period_ref, counterparty_uen, ack_id, " +
                        "acknowledged_at::text, state from evidence.submission_index where tenant_id = ?",
                ).use {
                    it.setObject(1, tenant)
                    it.executeQuery().use { rs ->
                        buildList { while (rs.next()) add((1..7).map { i -> rs.getString(i) }) }
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
    fun `INV-AC-20718 upsert writes the document columns with a null state and a repeat returns false`() {
        val t = UUID.randomUUID()
        upsert(t, row()) shouldBe true
        upsert(t, row()) shouldBe false
        rows(t) shouldBe listOf(listOf("INV-1", "2026-01-15", "2026-Q1", "201912345A", null, null, null))
    }

    @Test
    fun `INV-AC-20718 a second upsert with other values changes no column already set`() {
        val t = UUID.randomUUID()
        upsert(t, row())
        upsert(t, row("INV-2", LocalDate.of(2026, 2, 2), "OTHER", "2026-Q2")) shouldBe false
        rows(t) shouldBe listOf(listOf("INV-1", "2026-01-15", "2026-Q1", "201912345A", null, null, null))
    }

    @Test
    fun `INV-AC-20718 an upsert onto an acknowledgement-only row sets the document columns and keeps the ack`() {
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
        upsert(t, row()) shouldBe true
        val r = rows(t).single()
        r.take(5) shouldBe listOf("INV-1", "2026-01-15", "2026-Q1", "201912345A", "A1")
        r[5]!!.startsWith("2026-01-16") shouldBe true
        r[6] shouldBe "ACKNOWLEDGED"
    }

    @Test
    fun `INV-AC-20718 a second tenant reads nothing`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        upsert(a, row())
        tx(b) { dsl, _ ->
            dsl.fetchCount(DSL.table(DSL.name("evidence", "submission_index")))
        } shouldBe 0
    }

    @Test
    fun `INV-AC-20718 DELETE and TRUNCATE by evidence_runtime are refused`() {
        val t = UUID.randomUUID()
        upsert(t, row())
        refused(t, "delete from evidence.submission_index").sqlState shouldBe "42501"
        refused(t, "truncate evidence.submission_index").sqlState shouldBe "42501"
    }

    @Test
    fun `INV-AC-20718 a locator transaction rolled back leaves no index row`() {
        val t = UUID.randomUUID()
        EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, t)
            val dsl = DSL.using(c, SQLDialect.POSTGRES)
            val locator = DocumentLocator(doc, 1, "ENT-1", "2026-Q1", LocalDate.of(2026, 1, 15), "tr", "INV-1")
            ChainWiring.locatorRecorder(dsl).record(TenantId(t), "e1", locator, "201912345A") shouldBe true
            c.rollback()
        }
        rows(t) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20718 state is null after every document write and there is no category_code column`() {
        val t = UUID.randomUUID()
        upsert(t, row())
        upsert(t, row(number = null))
        rows(t).map { it[6] } shouldBe listOf(null)
        EvidenceDb.superuser().use { c ->
            c
                .prepareStatement(
                    "select count(*) from information_schema.columns where table_schema = 'evidence' and " +
                        "table_name = 'submission_index' and column_name = 'category_code'",
                ).use { s ->
                    s.executeQuery().use {
                        it.next()
                        it.getInt(1)
                    }
                } shouldBe 0
        }
    }
}
