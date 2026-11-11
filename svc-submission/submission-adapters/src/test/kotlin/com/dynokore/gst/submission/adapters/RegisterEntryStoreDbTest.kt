package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.persistence.JooqRegisterEntryStore
import com.dynokore.gst.submission.application.register.RegisterEntry
import com.dynokore.gst.submission.application.register.UpsertResult
import com.dynokore.gst.submission.domain.listing.Direction
import com.dynokore.gst.submission.domain.metrics.RowKind
import com.dynokore.gst.submission.domain.register.ListFacts
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** The register entry upsert store against the test database as `submission_runtime` (INV-AC-16339). */
class RegisterEntryStoreDbTest {
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

    private val store = JooqRegisterEntryStore(dsl, runner)

    private val expectedColumns =
        setOf(
            "tenant_id",
            "doc_uuid",
            "entity_ref",
            "period_ref",
            "due_date",
            "in_scope",
            "kind",
            "document_number",
            "document_type_code",
            "direction",
            "counterparty_name",
            "counterparty_uen",
            "taxable_amount",
            "gst_amount",
            "category_codes",
            "source_version",
        )

    @AfterEach
    fun close() = connection.close()

    private fun entry(
        doc: String,
        version: Int,
        number: String = "INV-$version",
        due: LocalDate = LocalDate.of(2026, 11, 30),
    ) = RegisterEntry(
        docUuid = doc,
        entityRef = "e1",
        periodRef = "p1",
        dueDate = due,
        inScope = true,
        kind = RowKind.INVOICE,
        facts =
            ListFacts(
                documentNumber = number,
                documentTypeCode = "380",
                direction = Direction.SALE,
                counterpartyName = "Acme Pte Ltd",
                counterpartyUen = "201912345A",
                taxableAmount = BigDecimal("100.00"),
                gstAmount = BigDecimal("9.00"),
                categoryCodes = setOf("SR", "ZR"),
            ),
        sourceVersion = version,
    )

    private fun upsert(
        tenant: UUID,
        e: RegisterEntry,
    ): UpsertResult {
        bound.set(tenant)
        try {
            return store.upsert(TenantId(tenant), e)
        } finally {
            bound.remove()
        }
    }

    /** The row as text, read as the tenant; null when the tenant sees no row. */
    private fun row(
        tenant: UUID,
        doc: String,
    ): String? {
        bound.set(tenant)
        try {
            return runner.inTransaction {
                dsl
                    .fetch(
                        "select t::text from submission.obligation_register_entry t where doc_uuid = ?",
                        doc,
                    ).firstOrNull()
                    ?.get(0, String::class.java)
            }
        } finally {
            bound.remove()
        }
    }

    private fun allOrNone(
        tenant: UUID,
        doc: String,
    ) {
        bound.set(tenant)
        try {
            val set =
                runner.inTransaction {
                    dsl
                        .fetchOne(
                            "select (document_number is not null)::int + (document_type_code is not null)::int + " +
                                "(direction is not null)::int + (counterparty_name is not null)::int + " +
                                "(counterparty_uen is not null)::int + (taxable_amount is not null)::int + " +
                                "(gst_amount is not null)::int + (category_codes is not null)::int " +
                                "from submission.obligation_register_entry where doc_uuid = ?",
                            doc,
                        )!!
                        .get(0, Int::class.java)
                }
            (set == 0 || set == 8) shouldBe true
        } finally {
            bound.remove()
        }
    }

    @Test
    fun `INV-AC-16339 insert then the same version again is UNCHANGED and the row is equal`() {
        val a = UUID.randomUUID()
        val doc = "d-$a"
        upsert(a, entry(doc, 1)) shouldBe UpsertResult.INSERTED
        val before = row(a, doc)
        upsert(a, entry(doc, 1, number = "REPLAY")) shouldBe UpsertResult.UNCHANGED
        row(a, doc) shouldBe before
        allOrNone(a, doc)
    }

    @Test
    fun `INV-AC-16339 a higher version is UPDATED and the row holds the new values`() {
        val a = UUID.randomUUID()
        val doc = "d-$a"
        upsert(a, entry(doc, 1))
        val newer = entry(doc, 3, due = LocalDate.of(2026, 12, 31))
        upsert(a, newer) shouldBe UpsertResult.UPDATED
        val text = row(a, doc)!!
        text.contains("INV-3") shouldBe true
        text.contains("2026-12-31") shouldBe true
        text.endsWith(",3)") shouldBe true
        allOrNone(a, doc)
    }

    @Test
    fun `INV-AC-16339 a lower version is UNCHANGED and the row keeps the higher version values`() {
        val a = UUID.randomUUID()
        val doc = "d-$a"
        upsert(a, entry(doc, 3))
        val before = row(a, doc)
        upsert(a, entry(doc, 2)) shouldBe UpsertResult.UNCHANGED
        row(a, doc) shouldBe before
        allOrNone(a, doc)
    }

    @Test
    fun `INV-AC-16339 an entry that exists without a source version is UPDATED by the first versioned write`() {
        val a = UUID.randomUUID()
        val doc = "d-$a"
        bound.set(a)
        runner.inTransaction {
            dsl.execute(
                "insert into submission.obligation_register_entry " +
                    "(tenant_id, doc_uuid, entity_ref, period_ref, due_date, in_scope, kind) " +
                    "values (?, ?, 'e1', 'p1', current_date, true, 'INVOICE')",
                a,
                doc,
            )
        }
        bound.remove()
        upsert(a, entry(doc, 1)) shouldBe UpsertResult.UPDATED
        allOrNone(a, doc)
    }

    /** A store on its own connection, bound to [tenant] in every transaction. */
    private fun storeOn(
        conn: java.sql.Connection,
        tenant: UUID,
    ): JooqRegisterEntryStore {
        val own =
            object : TransactionRunner {
                override fun <T> inTransaction(block: () -> T): T {
                    SignedBindings.bind(conn, tenant)
                    return block().also { conn.commit() }
                }
            }
        return JooqRegisterEntryStore(DSL.using(conn, SQLDialect.POSTGRES), own)
    }

    private fun storedVersion(
        tenant: UUID,
        doc: String,
    ): Int {
        bound.set(tenant)
        try {
            return runner.inTransaction {
                dsl
                    .fetchOne(
                        "select source_version from submission.obligation_register_entry where doc_uuid = ?",
                        doc,
                    )!!
                    .get(0, Int::class.java)
            }
        } finally {
            bound.remove()
        }
    }

    @Test
    fun `INV-AC-16339 two connections writing version 1 and 2 of a fresh document give exactly one INSERTED`() {
        val a = UUID.randomUUID()
        val pool =
            java.util.concurrent.Executors
                .newFixedThreadPool(2)
        val extra =
            SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).also {
                it.autoCommit =
                    false
            }
        try {
            val stores = listOf(storeOn(connection, a), storeOn(extra, a))
            repeat(20) {
                val doc = "race-${UUID.randomUUID()}"
                val gate = java.util.concurrent.CyclicBarrier(2)
                val futures =
                    stores.mapIndexed { n, st ->
                        pool.submit<UpsertResult> {
                            gate.await()
                            st.upsert(TenantId(a), entry(doc, n + 1))
                        }
                    }
                val results = futures.map { f -> f.get(30, java.util.concurrent.TimeUnit.SECONDS) }
                results.count { r -> r == UpsertResult.INSERTED } shouldBe 1
                storedVersion(a, doc) shouldBe 2
            }
        } finally {
            pool.shutdownNow()
            extra.close()
        }
    }

    @Test
    fun `INV-AC-16339 tenant B cannot update or see tenant A's row`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val doc = "d-$a"
        upsert(a, entry(doc, 1))
        val before = row(a, doc)
        row(b, doc) shouldBe null
        runCatching { upsert(b, entry(doc, 5, number = "OTHER")) }
        connection.rollback()
        row(b, doc) shouldBe null
        row(a, doc) shouldBe before
    }

    @Test
    fun `INV-AC-16339 the column set is the seven original the eight list-fact columns and source_version`() {
        val columns =
            SubmissionDb.superuser().use { c ->
                c.createStatement().use { st ->
                    st
                        .executeQuery(
                            "select column_name from information_schema.columns " +
                                "where table_schema = 'submission' and table_name = 'obligation_register_entry'",
                        ).use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
                }
            }
        columns.toSet() shouldBe expectedColumns
        columns.size shouldBe expectedColumns.size
    }
}
