package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.events.JacksonDocumentHeldDataReader
import com.dynokore.gst.submission.adapters.persistence.JooqProvisionalEntryStore
import com.dynokore.gst.submission.adapters.persistence.JooqRegisterEntryStore
import com.dynokore.gst.submission.application.register.Placement
import com.dynokore.gst.submission.application.register.ProvisionalEntry
import com.dynokore.gst.submission.application.register.RegisterEntry
import com.dynokore.gst.submission.domain.listing.Direction
import com.dynokore.gst.submission.domain.metrics.RowKind
import com.dynokore.gst.submission.domain.register.DocumentDirection
import com.dynokore.gst.submission.domain.register.ListFacts
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The provisional entry store and its deletion by the register writer, as `submission_runtime` (INV-AC-20691). */
class ProvisionalEntryStoreDbTest {
    private val connection =
        SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).also { it.autoCommit = false }
    private val dsl = DSL.using(connection, SQLDialect.POSTGRES)
    private val bound = ThreadLocal<UUID>()

    private val runner =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T {
                SignedBindings.bind(connection, checkNotNull(bound.get()))
                return try {
                    block().also { connection.commit() }
                } catch (e: Exception) {
                    connection.rollback()
                    throw e
                }
            }
        }

    private val store = JooqProvisionalEntryStore(dsl, runner)
    private val register = JooqRegisterEntryStore(dsl, runner)
    private val t1 = UUID.randomUUID()
    private val t2 = UUID.randomUUID()

    @AfterEach
    fun close() = connection.close()

    private fun <T> asTenant(
        tenant: UUID,
        block: () -> T,
    ): T {
        bound.set(tenant)
        try {
            return block()
        } finally {
            bound.remove()
        }
    }

    private fun provisional(
        doc: String,
        docsequence: Long = 1,
        period: String = "2026-06",
        date: LocalDate = LocalDate.of(2026, 6, 20),
        reason: String? = "ING-016",
    ) = ProvisionalEntry(
        docUuid = doc,
        entityRef = "E1",
        periodRef = period,
        direction = DocumentDirection.SALE,
        documentDate = LocalDate.of(2026, 6, 20),
        transactionDate = date,
        reasonCode = reason,
        docsequence = docsequence,
        heldAt = Instant.parse("2026-11-12T08:00:00Z"),
    )

    private fun place(
        entry: ProvisionalEntry,
        tenant: UUID = t1,
    ): Placement = asTenant(tenant) { store.place(TenantId(tenant), entry) }

    private fun rows(
        doc: String,
        tenant: UUID = t1,
    ): List<String> =
        asTenant(tenant) {
            runner.inTransaction {
                dsl
                    .fetch("select t::text from submission.provisional_register_entry t where doc_uuid = ?", doc)
                    .map { it.get(0, String::class.java) }
            }
        }

    private fun registerEntry(
        doc: String,
        version: Int,
    ) = RegisterEntry(
        docUuid = doc,
        entityRef = "E1",
        periodRef = "2026-06",
        dueDate = LocalDate.of(2026, 11, 30),
        inScope = true,
        kind = RowKind.INVOICE,
        facts =
            ListFacts(
                documentNumber = "INV-$version",
                documentTypeCode = "380",
                direction = Direction.SALE,
                counterpartyName = "Acme Pte Ltd",
                counterpartyUen = "201912345A",
                taxableAmount = BigDecimal("100.00"),
                gstAmount = BigDecimal("9.00"),
                categoryCodes = setOf("SR"),
            ),
        sourceVersion = version,
        transactionDate = LocalDate.of(2026, 6, 20),
    )

    @Test
    fun `the table grants exactly select insert update delete to the runtime role and forces row level security`() {
        val grants =
            dsl
                .fetch(
                    "select grantee, privilege_type from information_schema.role_table_grants " +
                        "where table_schema = 'submission' and table_name = 'provisional_register_entry' " +
                        "and grantee not in (select tableowner from pg_tables " +
                        "where schemaname = 'submission' and tablename = 'provisional_register_entry') " +
                        "order by 1, 2",
                ).map { "${it.get(0)}:${it.get(1)}" }
        grants shouldBe
            listOf(
                "submission_runtime:DELETE",
                "submission_runtime:INSERT",
                "submission_runtime:SELECT",
                "submission_runtime:UPDATE",
            )
        dsl
            .fetch(
                "select relrowsecurity, relforcerowsecurity from pg_class " +
                    "where oid = 'submission.provisional_register_entry'::regclass",
            ).map { "${it.get(0)}/${it.get(1)}" } shouldBe listOf("true/true")
    }

    @Test
    fun `INV-AC-20691 placements are stored once, newer ones replace, registered ones are refused`() {
        val d1 = UUID.randomUUID().toString()
        val d2 = UUID.randomUUID().toString()
        val d3 = UUID.randomUUID().toString()
        place(provisional(d3)) shouldBe Placement.PLACED
        val d3Before = rows(d3)
        place(provisional(d1)) shouldBe Placement.PLACED
        place(provisional(d1)) shouldBe Placement.UNCHANGED
        rows(d1).size shouldBe 1
        place(
            provisional(d1, docsequence = 2, period = "2026-07", date = LocalDate.of(2026, 7, 2), reason = "MDM-001"),
        ) shouldBe Placement.PLACED
        val row = rows(d1).single()
        listOf("2026-07", "2026-07-02", "MDM-001", ",2,").forEach { row.contains(it) shouldBe true }
        rows(d3) shouldBe d3Before
        asTenant(t1) { register.upsert(TenantId(t1), registerEntry(d2, 1)) }
        place(provisional(d2)) shouldBe Placement.ALREADY_REGISTERED
        rows(d2) shouldBe emptyList()
        asTenant(t2) { store.release(TenantId(t2), d1, 3) } shouldBe false
        rows(d1).single() shouldBe row
    }

    @Test
    fun `INV-AC-20691 tenant T2 reads no row of T1`() {
        val d1 = UUID.randomUUID().toString()
        place(provisional(d1)) shouldBe Placement.PLACED
        rows(d1, t2) shouldBe emptyList()
        rows(d1).size shouldBe 1
    }

    @Test
    fun `INV-AC-20691 a rejected register insert throws and keeps the provisional row`() {
        val d3 = UUID.randomUUID().toString()
        place(provisional(d3)) shouldBe Placement.PLACED
        // The entry carries tenant T2 while the session is bound to T1: the with-check of the register rejects it.
        shouldThrow<Exception> { asTenant(t1) { register.upsert(TenantId(t2), registerEntry(d3, 1)) } }
        rows(d3).size shouldBe 1
    }

    @Test
    fun `INV-AC-20691 the register insert and the second version remove the provisional row`() {
        val d1 = UUID.randomUUID().toString()
        place(provisional(d1)) shouldBe Placement.PLACED
        asTenant(t1) {
            runner.inTransaction {
                register.upsert(TenantId(t1), registerEntry(d1, 1))
                dsl
                    .fetch("select 1 from submission.provisional_register_entry where doc_uuid = ?", d1)
                    .size shouldBe 0
            }
        }
        place(provisional(d1, docsequence = 2)) shouldBe Placement.ALREADY_REGISTERED
        asTenant(t1) { register.upsert(TenantId(t1), registerEntry(d1, 2)) }
        rows(d1) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20691 release clears the reason only for a higher docsequence`() {
        val d1 = UUID.randomUUID().toString()
        place(provisional(d1)) shouldBe Placement.PLACED
        asTenant(t1) { store.release(TenantId(t1), d1, 1) } shouldBe false
        asTenant(t1) { store.release(TenantId(t1), d1, 2) } shouldBe true
        rows(d1).single().contains("ING-016") shouldBe false
        asTenant(t1) { store.release(TenantId(t1), UUID.randomUUID().toString(), 2) } shouldBe false
    }

    @Test
    fun `INV-AC-20691 the held reader keeps the date part as written and rejects malformed payloads`() {
        val reader = JacksonDocumentHeldDataReader()

        fun payload(extra: String) =
            """{"docUuid":"D1","holdReasons":["ING-016"],"entityRef":"E1","direction":"SALE",
               |"documentDate":"2026-06-20","prescribedPeriodRef":"2026-06"$extra}
            """.trimMargin()
        reader.read(payload(""","docsequence":1,"postingTimestamp":"2026-07-02T23:30:00+08:00"""")).postingDate shouldBe
            LocalDate.of(2026, 7, 2)
        reader.read(payload(""","docsequence":1""")).postingDate shouldBe null
        val badStamp =
            shouldThrow<ContractViolation> {
                reader.read(payload(""","docsequence":1,"postingTimestamp":"SECRET-9""""))
            }
        val noSequence = shouldThrow<ContractViolation> { reader.read(payload("")) }
        badStamp.message!! shouldNotContain "SECRET-9"
        noSequence.message!! shouldNotContain "D1"
    }
}
