package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.persistence.JooqObligationRowSource
import com.dynokore.gst.submission.domain.metrics.ObligationRow
import com.dynokore.gst.submission.domain.metrics.RowKind
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** The register reader against the test database as `submission_runtime` with the tenant bound (INV-AC-12839). */
class ObligationRowSourceDbTest {
    private val connection =
        SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).also { it.autoCommit = false }
    private val dsl = DSL.using(connection, SQLDialect.POSTGRES)
    private val bound = ThreadLocal<UUID>()

    private val runner =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T {
                SignedBindings.bind(connection, checkNotNull(bound.get()))
                return block().also { connection.commit() }
            }
        }

    private val reader = JooqObligationRowSource(dsl, runner)

    private fun <T> asTenant(
        tenant: UUID,
        block: () -> T,
    ): T {
        bound.set(tenant)
        try {
            return runner.inTransaction(block)
        } finally {
            bound.remove()
        }
    }

    private fun rowsOf(
        tenant: UUID,
        entityRef: String? = null,
        periodRef: String? = null,
    ): List<ObligationRow> {
        bound.set(tenant)
        try {
            return reader.rows(TenantId(tenant), entityRef, periodRef)
        } finally {
            bound.remove()
        }
    }

    private class Entry(
        val doc: String,
        val entity: String,
        val period: String,
        val kind: RowKind = RowKind.INVOICE,
        val states: List<String> = emptyList(),
        val has: Boolean = false,
    ) {
        var inScope: Boolean = true
    }

    @AfterEach
    fun close() = connection.close()

    private val due = LocalDate.of(2026, 11, 30)

    private fun seed(
        tenant: UUID,
        entries: List<Entry>,
    ) = asTenant(tenant) {
        entries.forEach { e ->
            dsl.execute(
                "insert into submission.obligation_register_entry " +
                    "(tenant_id, doc_uuid, entity_ref, period_ref, due_date, in_scope, kind) " +
                    "values (?, ?, ?, ?, ?, ?, ?)",
                tenant,
                e.doc,
                e.entity,
                e.period,
                due,
                e.inScope,
                e.kind.name,
            )
            e.states.forEachIndexed { index, state ->
                dsl.execute(
                    "insert into submission.submission (tenant_id, doc_uuid, version, state, entity_ref) " +
                        "values (?, ?, ?, ?, ?)",
                    tenant,
                    e.doc,
                    index + 1,
                    state,
                    e.entity,
                )
            }
        }
    }

    private fun expected(e: Entry) =
        ObligationRow(e.doc, e.entity, e.period, due, e.inScope, e.has, e.kind, maxOf(e.states.size, 1))

    /** Ten entries of tenant A over three entities and two periods; [has] is the expected hasSubmission. */
    private fun tenantAEntries(
        p: String,
        q: String,
    ): List<Entry> {
        val (e1, e2, e3) = listOf("e1-$p", "e2-$p", "e3-$p")
        return listOf(
            Entry("d01", e1, p, states = listOf("READY")).apply { inScope = false },
            Entry("d02", e2, q, states = listOf("HELD")),
            Entry("d03", e1, p, states = listOf("ACKNOWLEDGED"), has = true),
            Entry("d04", e1, p, states = listOf("QUEUED")),
            Entry("d05", e1, q),
            Entry("d06", e2, p, states = listOf("ACKNOWLEDGED", "REJECTED"), has = true),
            Entry("d07", e3, q, RowKind.CREDIT_NOTE, states = listOf("ACKNOWLEDGED", "SUPERSEDED", "SENT")),
            Entry("d08", e2, p, states = listOf("EXTERNALLY_SUBMITTED"), has = true),
            Entry("d09", e3, p, states = listOf("INGESTED", "REJECTED")),
            Entry("d10", e3, q, states = listOf("CANCELLED"), has = true),
        )
    }

    @Test
    fun `INV-AC-12839 the reader returns one row per document of the bound tenant filtered by entity and period`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val p = "p-${UUID.randomUUID()}".take(12)
        val q = "q-${UUID.randomUUID()}".take(12)
        val entries = tenantAEntries(p, q)
        seed(a, entries.reversed()) // reverse insertion order: the reader sorts by doc_uuid
        seed(b, listOf(Entry("b01", "e1-$p", p, states = listOf("ACKNOWLEDGED")), Entry("zz", "other-$p", p)))
        val expectedAll = entries.map(::expected)
        rowsOf(a) shouldBe expectedAll
        rowsOf(a, "e1-$p") shouldBe expectedAll.filter { it.entityRef == "e1-$p" }
        rowsOf(a, "e1-$p", p) shouldBe expectedAll.filter { it.entityRef == "e1-$p" && it.periodRef == p }
        rowsOf(a, periodRef = "no-such-period") shouldBe emptyList()
        rowsOf(b, "other-$p") shouldBe listOf(ObligationRow("zz", "other-$p", p, due, true, false, RowKind.INVOICE, 1))
    }
}
