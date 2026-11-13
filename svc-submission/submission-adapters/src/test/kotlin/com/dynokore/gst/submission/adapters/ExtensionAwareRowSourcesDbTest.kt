package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.persistence.JooqObligationRowSource
import com.dynokore.gst.submission.adapters.persistence.JooqSubmissionRowSource
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** Both register row sources map the effective due date of the period's extension rows (INV-AC-20599). */
class ExtensionAwareRowSourcesDbTest {
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

    private val obligations = JooqObligationRowSource(dsl, runner)
    private val submissions = JooqSubmissionRowSource(dsl, runner)
    private val register = LocalDate.of(2026, 4, 30)

    @AfterEach
    fun close() = connection.close()

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

    private fun seedDoc(
        tenant: UUID,
        doc: String,
        entity: String,
        period: String,
        tx: LocalDate,
    ) = asTenant(tenant) {
        dsl.execute(
            "insert into submission.obligation_register_entry (tenant_id, doc_uuid, entity_ref, period_ref, " +
                "due_date, " +
                "in_scope, kind, transaction_date, document_number, document_type_code, direction, " +
                "counterparty_name, " +
                "counterparty_uen, taxable_amount, gst_amount, category_codes) " +
                "values (?, ?, ?, ?, ?, true, 'INVOICE', ?, 'N-1', '380', 'SALE', 'Acme', '201912345A', " +
                "100.00, 9.00, array['SR'])",
            tenant,
            doc,
            entity,
            period,
            register,
            tx,
        )
        dsl.execute(
            "insert into submission.submission (tenant_id, doc_uuid, version, state, entity_ref, transaction_date, " +
                "submission_type) values (?, ?, 1, 'READY', ?, ?, 'TYPE_1A')",
            tenant,
            doc,
            entity,
            tx,
        )
    }

    private class Ext(
        val target: String,
        val kind: String,
        val ref: String?,
        val date: String,
    )

    private fun seedExtension(
        tenant: UUID,
        key: Pair<String, String>,
        ext: Ext,
    ) = asTenant(tenant) {
        dsl.execute(
            "insert into submission.due_date_extension (tenant_id, entity_ref, period_ref, target, scope_kind, " +
                "scope_ref, extended_due_date, iras_reference, source_event_id) " +
                "values (?, ?, ?, ?, ?, ?, ?, 'IRAS-1', ?)",
            tenant,
            key.first,
            key.second,
            ext.target,
            ext.kind,
            ext.ref,
            LocalDate.parse(ext.date),
            UUID.randomUUID().toString(),
        )
    }

    private fun obligationDates(
        tenant: UUID,
        entity: String,
        period: String,
    ): Map<String, LocalDate> {
        bound.set(tenant)
        try {
            return obligations.rows(TenantId(tenant), entity, period).associate { it.docUuid to it.dueDate }
        } finally {
            bound.remove()
        }
    }

    private fun submissionDates(
        tenant: UUID,
        entity: String,
        period: String,
    ): Map<String, LocalDate> {
        bound.set(tenant)
        try {
            return submissions.rows(TenantId(tenant), entity, period).rows.associate { it.docUuid to it.dueDate }
        } finally {
            bound.remove()
        }
    }

    private class Fixture(
        val a: UUID,
        val b: UUID,
        val e: String,
        val p: String,
        val q: String,
        val docs: List<String>,
    ) {
        val d1 get() = docs[0]
        val d2 get() = docs[1]
        val d4 get() = docs[2]
        val d3 get() = docs[3]
    }

    private fun fixture(): Fixture {
        val s = UUID.randomUUID().toString().take(8)
        val docs = listOf("d1-$s", "d2-$s", "d4-$s", "d3-$s")
        val f = Fixture(UUID.randomUUID(), UUID.randomUUID(), "e-$s", "p-$s", "q-$s", docs)
        seedDoc(f.a, f.d1, f.e, f.p, LocalDate.of(2026, 3, 10))
        seedDoc(f.a, f.d2, f.e, f.p, LocalDate.of(2026, 3, 20))
        seedDoc(f.a, f.d4, f.e, f.p, LocalDate.of(2026, 4, 2))
        seedDoc(f.a, f.d3, f.e, f.q, LocalDate.of(2026, 3, 20))
        val key = f.e to f.p
        val submission = "SUBMISSION_DUE_DATE"
        seedExtension(f.a, key, Ext(submission, "PERIOD", null, "2026-05-14"))
        seedExtension(f.a, key, Ext(submission, "DATE_RANGE", "2026-03-15/2026-03-31", "2026-06-01"))
        seedExtension(f.a, key, Ext(submission, "DOCUMENT_SET", f.d1, "2026-05-20"))
        seedExtension(f.a, key, Ext("FILING_DUE_DATE", "PERIOD", null, "2026-05-31"))
        seedExtension(f.a, key, Ext(submission, "PERIOD", null, "2026-05-16"))
        // The second tenant extends the same entity and period later; its rows are never read for tenant A.
        seedDoc(f.b, "b1-$s", f.e, f.p, LocalDate.of(2026, 3, 20))
        seedExtension(f.b, key, Ext(submission, "PERIOD", null, "2027-01-01"))
        return f
    }

    private fun expectedP(f: Fixture) =
        mapOf(
            f.d1 to LocalDate.of(2026, 5, 20),
            f.d2 to LocalDate.of(2026, 6, 1),
            f.d4 to LocalDate.of(2026, 5, 16),
        )

    @Test
    fun `INV-AC-20599 the obligation source returns the resolved date for P and the register date otherwise`() {
        val f = fixture()
        obligationDates(f.a, f.e, f.p) shouldBe expectedP(f)
        obligationDates(f.a, f.e, f.q) shouldBe mapOf(f.d3 to register)
    }

    @Test
    fun `INV-AC-20599 the submission source returns the resolved date for P and the register date otherwise`() {
        val f = fixture()
        submissionDates(f.a, f.e, f.p) shouldBe expectedP(f)
        submissionDates(f.a, f.e, f.q) shouldBe mapOf(f.d3 to register)
    }

    @Test
    fun `INV-AC-20599 with no extension rows both sources equal the register date`() {
        val s = UUID.randomUUID().toString().take(8)
        val tenant = UUID.randomUUID()
        seedDoc(tenant, "x1-$s", "e-$s", "p-$s", LocalDate.of(2026, 3, 10))
        seedDoc(tenant, "x2-$s", "e-$s", "p-$s", LocalDate.of(2026, 3, 20))
        val expected = mapOf("x1-$s" to register, "x2-$s" to register)
        obligationDates(tenant, "e-$s", "p-$s") shouldBe expected
        submissionDates(tenant, "e-$s", "p-$s") shouldBe expected
    }

    @Test
    fun `INV-AC-20599 a second tenant's extension rows are never read`() {
        val f = fixture()
        // Tenant B sees its own row only, and A's date is unchanged by B's later 2027 row.
        obligationDates(f.b, f.e, f.p).values.toSet() shouldBe setOf(LocalDate.of(2027, 1, 1))
        submissionDates(f.b, f.e, f.p).values.toSet() shouldBe setOf(LocalDate.of(2027, 1, 1))
        obligationDates(f.a, f.e, f.p)[f.d4] shouldBe LocalDate.of(2026, 5, 16)
        submissionDates(f.a, f.e, f.p)[f.d4] shouldBe LocalDate.of(2026, 5, 16)
    }
}
