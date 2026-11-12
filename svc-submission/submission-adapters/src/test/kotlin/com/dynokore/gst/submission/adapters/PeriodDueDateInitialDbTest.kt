package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.persistence.JooqPeriodDueDateStore
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.PeriodDueDate
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

/** The initial filing due date in the period due date table as `submission_runtime` (INV-AC-20795). */
class PeriodDueDateInitialDbTest {
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

    private fun initialise(
        t: UUID,
        period: String,
        date: String,
    ) = asTenant(t) { store.initialise(TenantId(t), "E1", period, LocalDate.parse(date)) }

    private fun find(
        t: UUID,
        period: String,
    ) = asTenant(t) { store.find(TenantId(t), "E1", period) }

    private fun version(
        t: UUID,
        period: String,
    ): Long? =
        asTenant(t) {
            runner.inTransaction {
                val p = PeriodDueDate.PERIOD_DUE_DATE
                dsl
                    .select(p.DUE_DATE_VERSION)
                    .from(p)
                    .where(p.ENTITY_REF.eq("E1"))
                    .and(p.PERIOD_REF.eq(period))
                    .fetchOne(p.DUE_DATE_VERSION)
            }
        }

    @Test
    fun `INV-AC-20795 the initial value is stored once with version 0 and a repeat changes nothing`() {
        val t = UUID.randomUUID()
        initialise(t, "2026-06", "2026-07-20")
        initialise(t, "2026-06", "2026-07-21")
        find(t, "2026-06") shouldBe LocalDate.of(2026, 7, 20)
        version(t, "2026-06") shouldBe 0L
    }

    @Test
    fun `INV-AC-20795 the initial value never overwrites a later version and a later version replaces it`() {
        val t = UUID.randomUUID()
        asTenant(t) { store.upsert(TenantId(t), "E1", "2026-07", LocalDate.parse("2026-08-20"), 3) }
        initialise(t, "2026-07", "2026-08-25")
        find(t, "2026-07") shouldBe LocalDate.of(2026, 8, 20)
        version(t, "2026-07") shouldBe 3L

        initialise(t, "2026-06", "2026-07-20")
        asTenant(t) { store.upsert(TenantId(t), "E1", "2026-06", LocalDate.parse("2026-07-22"), 1) }
        find(t, "2026-06") shouldBe LocalDate.of(2026, 7, 22)
        version(t, "2026-06") shouldBe 1L
        initialise(t, "2026-06", "2026-07-20")
        find(t, "2026-06") shouldBe LocalDate.of(2026, 7, 22)
    }

    @Test
    fun `INV-AC-20795 another tenant finds no row and its own initial value is separate`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        initialise(a, "2026-06", "2026-07-20")
        find(b, "2026-06") shouldBe null
        initialise(b, "2026-06", "2026-07-30")
        find(a, "2026-06") shouldBe LocalDate.of(2026, 7, 20)
        find(b, "2026-06") shouldBe LocalDate.of(2026, 7, 30)
    }

    @Test
    fun `INV-AC-20795 a direct insert with version -1 is refused by the table check`() {
        val t = UUID.randomUUID()
        shouldThrow<Exception> {
            asTenant(t) {
                try {
                    dsl.execute(
                        "insert into submission.period_due_date values (?, 'E1', 'P', date '2026-07-20', -1)",
                        t,
                    )
                } finally {
                    connection.rollback()
                }
            }
        }
    }
}
