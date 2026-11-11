package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.persistence.JooqRegisterDueDateReader
import com.dynokore.gst.submission.application.register.RegisterDueDate
import com.dynokore.gst.submission.application.register.RegisterDueDateReader
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** The register due-date reader against the test database as `submission_runtime` (INV-AC-20595). */
class RegisterDueDateReaderDbTest {
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

    private val reader: RegisterDueDateReader = JooqRegisterDueDateReader(dsl, runner)

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

    private fun insert(
        tenant: UUID,
        doc: String,
        entity: String,
        period: String,
        due: LocalDate,
    ) = asTenant(tenant) {
        dsl.execute(
            "insert into submission.obligation_register_entry " +
                "(tenant_id, doc_uuid, entity_ref, period_ref, due_date, in_scope, kind) " +
                "values (?, ?, ?, ?, ?, true, 'INVOICE')",
            tenant,
            doc,
            entity,
            period,
            due,
        )
    }

    private fun count(tenant: UUID): Int =
        asTenant(tenant) { dsl.fetchOne("select count(*) from submission.obligation_register_entry")!!.get(0, Int::class.java) }

    private fun read(
        tenant: UUID,
        entity: String,
        period: String,
    ) = reader.forPeriod(TenantId(tenant), entity, period)

    @Test
    fun `INV-AC-20595 the reader returns every entry of the entity and period of the bound tenant only`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val s = a.toString()
        val d1 = LocalDate.of(2026, 4, 30)
        val d2 = LocalDate.of(2026, 5, 31)
        insert(a, "$s-1", "E", "P", d1)
        insert(a, "$s-2", "E", "P", d2)
        insert(a, "$s-3", "E", "P2", d1)
        insert(a, "$s-4", "e", "P", d1)
        insert(a, "$s-5", "F", "P", d1)
        insert(b, "${b}-1", "E", "P", d1)
        val before = count(a)
        read(a, "E", "P").shouldContainExactlyInAnyOrder(RegisterDueDate("$s-1", d1), RegisterDueDate("$s-2", d2))
        read(a, "E", "P3").shouldBeEmpty()
        read(a, "e", "P").map { it.docUuid } shouldBe listOf("$s-4")
        read(b, "E", "P").map { it.docUuid } shouldBe listOf("${b}-1")
        read(UUID.randomUUID(), "E", "P").shouldBeEmpty()
        count(a) shouldBe before
        count(b) shouldBe 1
    }
}
