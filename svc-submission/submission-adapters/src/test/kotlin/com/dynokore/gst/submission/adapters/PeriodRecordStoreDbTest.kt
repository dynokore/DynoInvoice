package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.persistence.JooqPeriodRecordStore
import com.dynokore.gst.submission.application.period.PeriodRecord
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** The period record store and lookup against the test database as `submission_runtime` (INV-AC-20678). */
class PeriodRecordStoreDbTest {
    private val connection =
        SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).also { it.autoCommit = false }
    private val dsl = DSL.using(connection, SQLDialect.POSTGRES)
    private val bound = ThreadLocal<UUID>()

    /** Commits on success and rolls back on any failure, as the tenant transaction of the consumer does. */
    private val runner =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T {
                SignedBindings.bind(connection, checkNotNull(bound.get()))
                return try {
                    block().also { connection.commit() }
                } catch (e: Throwable) {
                    connection.rollback()
                    throw e
                }
            }
        }

    private val store = JooqPeriodRecordStore(dsl, runner)

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

    private fun d(text: String) = LocalDate.parse(text)

    private val q1 = PeriodRecord("Q1", d("2026-01-01"), d("2026-03-31"))
    private val q2 = PeriodRecord("Q2", d("2026-04-01"), d("2026-06-30"))
    private val q2Short = PeriodRecord("Q2", d("2026-04-01"), d("2026-06-29"))
    private val q3 = PeriodRecord("Q3", d("2026-07-01"), d("2026-09-30"))

    private fun replace(
        tenant: UUID,
        version: Int,
        vararg periods: PeriodRecord,
        entity: String = "E-001",
    ) = asTenant(tenant) { store.replace(TenantId(tenant), entity, version, periods.toList()) }

    private fun containing(
        tenant: UUID,
        date: String,
        entity: String = "E-001",
    ) = asTenant(tenant) { store.containing(TenantId(tenant), entity, d(date)) }

    private fun rows(
        tenant: UUID,
        entity: String = "E-001",
    ): List<String> =
        asTenant(tenant) {
            runner.inTransaction {
                dsl
                    .resultQuery(
                        "select period_ref || ':' || period_start || ':' || period_end || ':' || version " +
                            "from submission.period_record where entity_ref = ? order by period_ref",
                        entity,
                    ).fetch(0, String::class.java)
            }
        }

    @Test
    fun `INV-AC-20678 replace stores the periods with the event version and highestVersion reads it`() {
        val t = UUID.randomUUID()
        asTenant(t) { store.highestVersion(TenantId(t), "E-001") } shouldBe null
        replace(t, 1, q1, q2)
        rows(t) shouldBe listOf("Q1:2026-01-01:2026-03-31:1", "Q2:2026-04-01:2026-06-30:1")
        asTenant(t) { store.highestVersion(TenantId(t), "E-001") } shouldBe 1
        asTenant(t) { store.highestVersion(TenantId(t), "E-002") } shouldBe null
    }

    @Test
    fun `INV-AC-20678 replace upserts the present periods and removes the absent one`() {
        val t = UUID.randomUUID()
        replace(t, 1, q1, q2)
        replace(t, 2, q2Short, q3)
        rows(t) shouldBe listOf("Q2:2026-04-01:2026-06-29:2", "Q3:2026-07-01:2026-09-30:2")
        asTenant(t) { store.highestVersion(TenantId(t), "E-001") } shouldBe 2
    }

    @Test
    fun `INV-AC-20678 replace of one entity leaves another entity of the tenant alone`() {
        val t = UUID.randomUUID()
        replace(t, 1, q1, entity = "E-002")
        replace(t, 1, q2)
        replace(t, 2, q3)
        rows(t, "E-002") shouldBe listOf("Q1:2026-01-01:2026-03-31:1")
        rows(t) shouldBe listOf("Q3:2026-07-01:2026-09-30:2")
    }

    @Test
    fun `INV-AC-20678 a failing replace leaves the previous set intact`() {
        val t = UUID.randomUUID()
        replace(t, 1, q1, q2)
        // the second period ends before it starts: the check fails after the absent rows were deleted
        val bad = PeriodRecord("Q3", d("2026-09-30"), d("2026-07-01"))
        shouldThrow<Exception> { replace(t, 2, q2Short, bad) }
        rows(t) shouldBe listOf("Q1:2026-01-01:2026-03-31:1", "Q2:2026-04-01:2026-06-30:1")
    }

    @Test
    fun `INV-AC-20678 containing includes both ends and answers nothing outside`() {
        val t = UUID.randomUUID()
        replace(t, 1, q1, q2)
        containing(t, "2026-01-01") shouldBe listOf("Q1")
        containing(t, "2026-03-31") shouldBe listOf("Q1")
        containing(t, "2026-04-01") shouldBe listOf("Q2")
        containing(t, "2026-06-30") shouldBe listOf("Q2")
        containing(t, "2026-12-31") shouldBe emptyList()
        containing(t, "2026-03-31", "E-002") shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20678 containing returns two references for a date in two rows`() {
        val t = UUID.randomUUID()
        val a = PeriodRecord("A", d("2026-01-01"), d("2026-06-30"))
        replace(t, 1, a, PeriodRecord("B", d("2026-03-01"), d("2026-09-30")))
        containing(t, "2026-04-01") shouldBe listOf("A", "B")
    }

    @Test
    fun `INV-AC-20678 a tenant reads, replaces and removes only its own rows`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        replace(a, 1, q2)
        replace(b, 1, q1)
        rows(a) shouldBe listOf("Q2:2026-04-01:2026-06-30:1")
        rows(b) shouldBe listOf("Q1:2026-01-01:2026-03-31:1")
        containing(b, "2026-05-01") shouldBe emptyList()
        asTenant(b) { store.highestVersion(TenantId(b), "E-001") } shouldBe 1
        replace(a, 2, q3)
        rows(b) shouldBe listOf("Q1:2026-01-01:2026-03-31:1")
        // a write for another tenant than the bound one is refused by the policy
        shouldThrow<Exception> { asTenant(a) { store.replace(TenantId(b), "E-001", 5, listOf(q3)) } }
        rows(b) shouldBe listOf("Q1:2026-01-01:2026-03-31:1")
    }
}
