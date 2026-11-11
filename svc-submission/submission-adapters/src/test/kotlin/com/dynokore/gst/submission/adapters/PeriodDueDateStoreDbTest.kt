package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.persistence.JooqPeriodDueDateStore
import com.dynokore.gst.submission.application.register.UpsertResult
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** The period due date store against the test database as `submission_runtime` (INV-AC-20513). */
class PeriodDueDateStoreDbTest {
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

    private val store = JooqPeriodDueDateStore(dsl, runner)

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

    private fun upsert(
        tenant: UUID,
        date: LocalDate,
        sequence: Long,
        entity: String = "E",
    ) = asTenant(tenant) { store.upsert(TenantId(tenant), entity, "P", date, sequence) }

    private fun find(
        tenant: UUID,
        entity: String = "E",
    ) = asTenant(tenant) { store.find(TenantId(tenant), entity, "P") }

    @Test
    fun `INV-AC-20513 an insert stores the date and find reads it`() {
        val a = UUID.randomUUID()
        upsert(a, LocalDate.of(2026, 7, 31), 1) shouldBe UpsertResult.INSERTED
        find(a) shouldBe LocalDate.of(2026, 7, 31)
        find(a, "E2") shouldBe null
    }

    @Test
    fun `INV-AC-20513 a higher sequence updates the date`() {
        val a = UUID.randomUUID()
        upsert(a, LocalDate.of(2026, 7, 31), 1)
        upsert(a, LocalDate.of(2026, 8, 31), 2) shouldBe UpsertResult.UPDATED
        find(a) shouldBe LocalDate.of(2026, 8, 31)
    }

    @Test
    fun `INV-AC-20513 an equal or lower sequence changes nothing`() {
        val a = UUID.randomUUID()
        upsert(a, LocalDate.of(2026, 7, 31), 5)
        upsert(a, LocalDate.of(2026, 9, 30), 5) shouldBe UpsertResult.UNCHANGED
        upsert(a, LocalDate.of(2026, 9, 30), 4) shouldBe UpsertResult.UNCHANGED
        find(a) shouldBe LocalDate.of(2026, 7, 31)
    }

    @Test
    fun `INV-AC-20513 a row of another tenant is never read nor changed`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        upsert(a, LocalDate.of(2026, 7, 31), 1)
        find(b) shouldBe null
        upsert(b, LocalDate.of(2026, 10, 31), 1) shouldBe UpsertResult.INSERTED
        find(a) shouldBe LocalDate.of(2026, 7, 31)
        find(b) shouldBe LocalDate.of(2026, 10, 31)
    }
}
