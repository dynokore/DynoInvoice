package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.persistence.JooqRegisterEntryStore
import com.dynokore.gst.submission.adapters.persistence.JooqRegisteredDocuments
import com.dynokore.gst.submission.application.arrival.RegisteredDocument
import com.dynokore.gst.submission.application.register.RegisterEntry
import com.dynokore.gst.submission.domain.listing.Direction
import com.dynokore.gst.submission.domain.metrics.RowKind
import com.dynokore.gst.submission.domain.register.ListFacts
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.tools.jdbc.MockConnection
import org.jooq.tools.jdbc.MockResult
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** The register reader against the test database as `submission_runtime` (INV-AC-21093, INV-T-21094). */
class RegisteredDocumentsDbTest {
    private val connection =
        SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).also { it.autoCommit = false }
    private val dsl = DSL.using(connection, SQLDialect.POSTGRES)
    private val bound = ThreadLocal<UUID>()
    private val created = mutableListOf<UUID>()

    private val runner =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T {
                SignedBindings.bind(connection, checkNotNull(bound.get()))
                return block().also { connection.commit() }
            }
        }

    private val writer = JooqRegisterEntryStore(dsl, runner)
    private val reader = JooqRegisteredDocuments(dsl)

    @AfterEach
    fun cleanUp() {
        connection.close()
        SubmissionDb.superuser().use { c ->
            created.forEach { t ->
                c.createStatement().use {
                    it.execute("delete from submission.obligation_register_entry where tenant_id = '$t'")
                }
            }
        }
    }

    private fun tenant() = UUID.randomUUID().also { created += it }

    private fun put(
        tenant: UUID,
        doc: String,
        kind: RowKind,
    ) {
        bound.set(tenant)
        try {
            writer.upsert(
                TenantId(tenant),
                RegisterEntry(
                    docUuid = doc,
                    entityRef = "E-001",
                    periodRef = "2026-Q1",
                    dueDate = LocalDate.of(2026, 4, 30),
                    inScope = true,
                    kind = kind,
                    facts =
                        ListFacts(
                            documentNumber = "N-$doc",
                            documentTypeCode = "381",
                            direction = Direction.SALE,
                            counterpartyName = "Acme Pte Ltd",
                            counterpartyUen = "201912345A",
                            taxableAmount = BigDecimal("100.00"),
                            gstAmount = BigDecimal("9.00"),
                            categoryCodes = setOf("SR"),
                        ),
                    sourceVersion = 1,
                ),
            )
        } finally {
            bound.remove()
        }
    }

    private fun find(
        tenant: UUID,
        doc: String,
    ): RegisteredDocument? {
        SignedBindings.bind(connection, tenant)
        return try {
            reader.find(TenantId(tenant), doc)
        } finally {
            connection.rollback()
        }
    }

    /** The connection is bound to [boundTo] (row level security) while the reader is asked for [asked]. */
    private fun findAsked(
        boundTo: UUID,
        asked: UUID,
        doc: String,
    ): RegisteredDocument? {
        SignedBindings.bind(connection, boundTo)
        return try {
            reader.find(TenantId(asked), doc)
        } finally {
            connection.rollback()
        }
    }

    private fun count(): Int =
        SubmissionDb.superuser().use { c ->
            c.createStatement().use { st ->
                st
                    .executeQuery(
                        "select count(*) from submission.obligation_register_entry where tenant_id in (" +
                            created.joinToString(",") { "'$it'" } + ")",
                    ).use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
            }
        }

    @Test
    fun `INV-AC-21093 find returns the entry of the bound tenant only and null for none`() {
        val a = tenant()
        val b = tenant()
        val d1 = "d1-" + UUID.randomUUID()
        val d2 = "d2-" + UUID.randomUUID()
        put(a, d1, RowKind.CREDIT_NOTE)
        put(b, d2, RowKind.INVOICE)
        val before = count()
        find(a, d1) shouldBe RegisteredDocument("E-001", "2026-Q1", RowKind.CREDIT_NOTE)
        find(a, d2) shouldBe null
        find(a, "unknown-" + UUID.randomUUID()) shouldBe null
        find(b, d1) shouldBe null
        find(b, d2) shouldBe RegisteredDocument("E-001", "2026-Q1", RowKind.INVOICE)
        // The query's own tenant condition holds even when row level security would show the row.
        findAsked(a, b, d1) shouldBe null
        findAsked(a, a, d1) shouldBe RegisteredDocument("E-001", "2026-Q1", RowKind.CREDIT_NOTE)
        count() shouldBe before
    }

    @Test
    fun `INV-AC-21093 an unmapped kind fails closed`() {
        val a = tenant()
        val d = "d-" + UUID.randomUUID()
        put(a, d, RowKind.INVOICE)
        // The stubbed reader sees a kind value outside RowKind; the table check cannot store one.
        val stub =
            MockConnection { _ ->
                val create = DSL.using(SQLDialect.POSTGRES)
                val f1 = DSL.field("entity_ref", String::class.java)
                val f2 = DSL.field("period_ref", String::class.java)
                val f3 = DSL.field("kind", String::class.java)
                val result = create.newResult(f1, f2, f3)
                result.add(create.newRecord(f1, f2, f3).also { it.values("E", "P", "OTHER") })
                arrayOf(MockResult(1, result))
            }
        assertThrows<IllegalStateException> {
            JooqRegisteredDocuments(DSL.using(stub, SQLDialect.POSTGRES)).find(TenantId(a), d)
        }
    }
}
