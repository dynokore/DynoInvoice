package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.persistence.JooqRegisterListFactsStore
import com.dynokore.gst.submission.domain.listing.Direction
import com.dynokore.gst.submission.domain.register.ListFacts
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** The register list-facts store against the test database as `submission_runtime` (INV-AC-16322). */
class RegisterListFactsStoreDbTest {
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

    private val store = JooqRegisterListFactsStore(dsl, runner)

    private val newColumns =
        setOf(
            "document_number",
            "document_type_code",
            "direction",
            "counterparty_name",
            "counterparty_uen",
            "taxable_amount",
            "gst_amount",
            "category_codes",
        )
    private val oldColumns =
        setOf("tenant_id", "doc_uuid", "entity_ref", "period_ref", "due_date", "in_scope", "kind")

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

    private fun seed(
        tenant: UUID,
        doc: String,
    ) = asTenant(tenant) {
        dsl.execute(
            "insert into submission.obligation_register_entry " +
                "(tenant_id, doc_uuid, entity_ref, period_ref, due_date, in_scope, kind) values (?, ?, ?, ?, ?, ?, ?)",
            tenant,
            doc,
            "e1",
            "p1",
            LocalDate.of(2026, 11, 30),
            true,
            "INVOICE",
        )
    }

    private fun count(tenant: UUID): Int =
        asTenant(tenant) {
            dsl.fetchOne("select count(*) from submission.obligation_register_entry")!!.get(0, Int::class.java)!!
        }

    private fun facts(
        direction: Direction,
        categories: Set<String>,
        number: String = "INV-1",
    ) = ListFacts(
        documentNumber = number,
        documentTypeCode = if (direction == Direction.SALE) "380" else "381",
        direction = direction,
        counterpartyName = "Acme Pte Ltd",
        counterpartyUen = "201912345A",
        taxableAmount = BigDecimal("100.00"),
        gstAmount = BigDecimal("9.00"),
        categoryCodes = categories,
    )

    private fun read(
        tenant: UUID,
        doc: String,
    ): ListFacts? = asTenant(tenant) { store.facts(TenantId(tenant), doc) }

    private fun write(
        tenant: UUID,
        doc: String,
        f: ListFacts,
    ): Boolean = asTenant(tenant) { store.store(TenantId(tenant), doc, f) }

    @Test
    fun `INV-AC-16322 storing and reading back equals the input for SALE and PURCHASE and both category sets`() {
        val a = UUID.randomUUID()
        val docs = listOf("d1-$a", "d2-$a")
        docs.forEach { seed(a, it) }
        val sale = facts(Direction.SALE, emptySet())
        val purchase = facts(Direction.PURCHASE, setOf("SR", "ZR"))
        write(a, docs[0], sale) shouldBe true
        write(a, docs[1], purchase) shouldBe true
        read(a, docs[0]) shouldBe sale
        read(a, docs[1]) shouldBe purchase
    }

    @Test
    fun `INV-AC-16322 an entry without stored facts reads null and a second store replaces the first`() {
        val a = UUID.randomUUID()
        val doc = "d-$a"
        seed(a, doc)
        read(a, doc) shouldBe null
        write(a, doc, facts(Direction.SALE, setOf("SR"))) shouldBe true
        val second = facts(Direction.PURCHASE, setOf("ZR", "ES"), "INV-2")
        write(a, doc, second) shouldBe true
        read(a, doc) shouldBe second
    }

    @Test
    fun `INV-AC-16322 a document with no entry returns false and the row count stays equal`() {
        val a = UUID.randomUUID()
        seed(a, "d-$a")
        val before = count(a)
        write(a, "missing-$a", facts(Direction.SALE, emptySet())) shouldBe false
        count(a) shouldBe before
        read(a, "missing-$a") shouldBe null
    }

    @Test
    fun `INV-AC-16322 tenant B cannot read or store the facts of tenant A's entry`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val doc = "d-$a"
        seed(a, doc)
        val own = facts(Direction.SALE, setOf("SR"))
        write(a, doc, own) shouldBe true
        read(b, doc) shouldBe null
        write(b, doc, facts(Direction.PURCHASE, emptySet(), "OTHER")) shouldBe false
        read(a, doc) shouldBe own
    }

    @Test
    fun `INV-AC-16322 the column set is the seven old columns plus exactly the eight new names`() {
        val columns =
            dsl
                .fetch(
                    "select column_name from information_schema.columns " +
                        "where table_schema = 'submission' and table_name = 'obligation_register_entry'",
                ).map { it.get(0, String::class.java)!! }
        columns.toSet() shouldBe oldColumns + newColumns
        columns.size shouldBe oldColumns.size + newColumns.size
    }

    @Test
    fun `INV-AC-16322 inserting an entry with only some of the eight columns is refused by the check`() {
        val a = UUID.randomUUID()
        val ex =
            shouldThrow<Exception> {
                asTenant(a) {
                    dsl.execute(
                        "insert into submission.obligation_register_entry " +
                            "(tenant_id, doc_uuid, entity_ref, period_ref, due_date, in_scope, kind, " +
                            "document_number) " +
                            "values (?, ?, 'e', 'p', current_date, true, 'INVOICE', 'X')",
                        a,
                        "partial-$a",
                    )
                }
            }
        val refused =
            generateSequence<Throwable>(ex) { it.cause }
                .any { it.message.orEmpty().contains("list_facts_all_or_none") }
        refused shouldBe true
        connection.rollback()
    }

    @Test
    fun `INV-AC-16322 no other table of the schema has a column of those names`() {
        val others =
            dsl
                .fetch(
                    "select table_name, column_name from information_schema.columns " +
                        "where table_schema = 'submission' and table_name <> 'obligation_register_entry' " +
                        "and column_name in (${newColumns.joinToString(",") { "'$it'" }})",
                ).map { "${it.get(0)}.${it.get(1)}" }
        others shouldBe emptyList()
    }

    @Test
    fun `INV-AC-16322 the facts are removed with the entry row`() {
        val a = UUID.randomUUID()
        val doc = "d-$a"
        seed(a, doc)
        write(a, doc, facts(Direction.SALE, setOf("SR"))) shouldBe true
        SubmissionDb.superuser().use { c ->
            c.prepareStatement("delete from submission.obligation_register_entry where doc_uuid = ?").use {
                it.setString(1, doc)
                it.executeUpdate() shouldBe 1
            }
        }
        read(a, doc) shouldBe null
    }
}
