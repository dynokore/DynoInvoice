package com.dynokore.gst.consoleBff.adapters.exceptions

import com.dynokore.gst.consoleBff.adapters.ConsoleDb
import com.dynokore.gst.consoleBff.adapters.SignedBindings
import com.dynokore.gst.consoleBff.adapters.duedates.JooqPeriodDueDateStore
import com.dynokore.gst.consoleBff.application.duedates.PeriodKey
import com.dynokore.gst.consoleBff.application.exceptions.QueuedException
import com.dynokore.gst.consoleBff.domain.badge.ItemState
import com.dynokore.gst.consoleBff.domain.exceptions.ExceptionRow
import com.dynokore.gst.consoleBff.domain.exceptions.ExceptionSortKey
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * `JooqExceptionQueue` on a live PostgreSQL as the runtime role under a signed binding (INV-AC-21105): order by due
 * date (unknown last), raisedAt, exceptionId; keyset paging; entity filter; tenant isolation; deferral filter.
 */
class ExceptionQueueDbTest {
    private val tenantA = UUID.randomUUID()
    private val tenantB = UUID.randomUUID()

    private fun id(suffix: Int) = UUID.fromString("00000000-0000-0000-0000-00000000000$suffix")

    @Suppress("LongParameterList") // a fixture row names each stored column it varies
    private fun row(
        tenant: UUID,
        n: Int,
        entity: String,
        period: String,
        raisedAt: String,
        state: ItemState = ItemState.OPEN,
        owner: String = "owner-x",
        code: String = "PAY-006",
    ) = ExceptionRow(
        tenantId = tenant.toString(),
        exceptionId = id(n),
        entityRef = entity,
        docUuid = "doc-$n",
        code = code,
        remediationOwner = owner,
        disposition = "FIX",
        state = state,
        raisedAt = Instant.parse("2026-11-10T$raisedAt:00Z"),
        periodRef = period,
        docsequence = n.toLong(),
    )

    private fun <T> asTenant(
        tenant: UUID,
        block: (Connection) -> T,
    ): T =
        ConsoleDb.connect(ConsoleDb.RUNTIME, ConsoleDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, tenant)
            val result = block(c)
            c.commit()
            result
        }

    private fun dsl(c: Connection) = DSL.using(c, SQLDialect.POSTGRES)

    private fun deferral(
        tenant: UUID,
        entity: String,
        kind: String,
        ref: String,
        state: String = "DEFERRED",
    ) = asTenant(tenant) { c ->
        c
            .prepareStatement(
                "insert into console_bff.deferral (tenant_id, deferral_id, entity_ref, target_kind, target_ref, " +
                    "state, deferred_by, deferred_at) values (?, ?, ?, ?, ?, ?, 'user-1', now())",
            ).use {
                it.setObject(1, tenant)
                it.setObject(2, UUID.randomUUID())
                it.setString(3, entity)
                it.setString(4, kind)
                it.setString(5, ref)
                it.setString(6, state)
                it.executeUpdate()
            }
    }

    private fun seed() {
        asTenant(tenantA) { c ->
            JooqExceptionStore(dsl(c)).upsert(
                listOf(
                    row(tenantA, 1, "E-001", "2026-Q1", "10:00"),
                    row(tenantA, 2, "E-001", "2026-Q1", "09:00"),
                    row(tenantA, 3, "E-002", "2026-Q2", "08:00"),
                    row(tenantA, 4, "E-002", "2026-Q2", "08:00", ItemState.RESOLVED, code = "PAY-011"),
                    row(tenantA, 5, "E-001", "", "07:00"),
                    row(tenantA, 6, "E-003", "2026-Q1", "06:00"),
                ),
            )
            val dues = JooqPeriodDueDateStore(dsl(c))
            dues.applyIfNewer(tenantA.toString(), PeriodKey("E-001", "2026-Q1"), LocalDate.parse("2026-11-21"), 1)
            dues.applyIfNewer(tenantA.toString(), PeriodKey("E-002", "2026-Q2"), LocalDate.parse("2026-11-01"), 1)
        }
        asTenant(tenantB) { c ->
            JooqExceptionStore(dsl(c)).upsert(listOf(row(tenantB, 7, "E-001", "2026-Q1", "05:00")))
        }
    }

    private fun seedDeferrals() {
        deferral(tenantA, "E-001", "DOCUMENT", "doc-2")
        deferral(tenantA, "E-002", "GROUP", "owner-x/PAY-006")
        deferral(tenantA, "E-001", "DOCUMENT", "doc-1", "UNDONE")
        deferral(tenantA, "E-009", "DOCUMENT", "doc-5")
        deferral(tenantB, "E-001", "DOCUMENT", "doc-1")
    }

    private fun key(q: QueuedException) = ExceptionSortKey(q.dueDate, q.row.raisedAt, q.row.exceptionId.toString())

    /** Pages with [limit] until a page is shorter than the limit; returns every page's rows in order. */
    private fun drain(
        tenant: UUID,
        entities: Set<String>,
        state: ItemState? = null,
        deferred: Boolean = false,
        limit: Int = 2,
    ): List<QueuedException> =
        asTenant(tenant) { c ->
            val queue = JooqExceptionQueue(dsl(c))
            buildList {
                var after: ExceptionSortKey? = null
                repeat(MAX_PAGES) {
                    val page = queue.page(tenant.toString(), entities, state, deferred, after, limit)
                    addAll(page)
                    if (page.size < limit) return@buildList
                    after = key(page.last())
                }
            }
        }

    private fun ids(rows: List<QueuedException>) = rows.map { it.row.exceptionId }

    private fun count(sql: String): Long =
        ConsoleDb.superuser().use { c ->
            c.createStatement().use { s ->
                s.executeQuery(sql).use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        }

    private companion object {
        /** A broken keyset condition repeats rows forever; bound the paging so the case fails instead of hanging. */
        const val MAX_PAGES = 20
    }

    private val both = setOf("E-001", "E-002")

    @Test
    fun `INV-AC-21105 pages in due date order unknown last without gap or repeat and carries the row and due date`() {
        seed()
        val rows = drain(tenantA, both, deferred = false)
        ids(rows) shouldContainExactly listOf(id(3), id(4), id(2), id(1), id(5))
        rows.map { it.dueDate?.toString() } shouldContainExactly
            listOf("2026-11-01", "2026-11-01", "2026-11-21", "2026-11-21", null)
        rows[0].row shouldBe row(tenantA, 3, "E-002", "2026-Q2", "08:00")
        rows[4].row shouldBe row(tenantA, 5, "E-001", "", "07:00")
        ids(rows).none { it == id(6) } shouldBe true
    }

    @Test
    fun `INV-AC-21105 a page of one and a page larger than the data give the same order`() {
        seed()
        ids(drain(tenantA, both, limit = 1)) shouldContainExactly listOf(id(3), id(4), id(2), id(1), id(5))
        ids(drain(tenantA, both, limit = 50)) shouldContainExactly listOf(id(3), id(4), id(2), id(1), id(5))
    }

    @Test
    fun `INV-AC-21105 state OPEN leaves out the resolved row and one entity leaves out the other`() {
        seed()
        ids(drain(tenantA, both, state = ItemState.OPEN)) shouldContainExactly listOf(id(3), id(2), id(1), id(5))
        ids(drain(tenantA, setOf("E-001"))) shouldContainExactly listOf(id(2), id(1), id(5))
    }

    @Test
    fun `INV-AC-21105 an empty entity set gives no row and tenant B sees only its own row`() {
        seed()
        drain(tenantA, emptySet()) shouldBe emptyList()
        ids(drain(tenantB, both)) shouldContainExactly listOf(id(7))
        drain(tenantB, both).single().dueDate shouldBe null
    }

    @Test
    fun `INV-AC-21105 the statement names due date nulls last, any, left join and exists`() {
        val sql =
            ConsoleDb.connect(ConsoleDb.RUNTIME, ConsoleDb.RUNTIME_PASSWORD).use { c ->
                JooqExceptionQueue(dsl(c))
                    .statement(tenantA, both, null, true, null, 2)
                    .getSQL()
            }
        sql shouldContain "nulls last"
        sql shouldContain Regex("= any ?\\(")
        sql shouldContain Regex("left (outer )?join")
        sql shouldContain "exists"
    }

    @Test
    fun `INV-AC-21105 deferred false lists the uncovered rows and deferred true only the covered rows`() {
        seed()
        seedDeferrals()
        val hidden = drain(tenantA, both, deferred = false)
        ids(hidden) shouldContainExactly listOf(id(4), id(1), id(5))
        hidden.map { it.dueDate?.toString() } shouldContainExactly listOf("2026-11-01", "2026-11-21", null)
        val deferred = drain(tenantA, both, deferred = true)
        ids(deferred) shouldContainExactly listOf(id(3), id(2))
        deferred.map { it.dueDate?.toString() } shouldContainExactly listOf("2026-11-01", "2026-11-21")
        ids(drain(tenantA, both, deferred = true, limit = 1)) shouldContainExactly listOf(id(3), id(2))
        ids(drain(tenantA, both, deferred = false, limit = 1)) shouldContainExactly listOf(id(4), id(1), id(5))
    }

    @Test
    fun `INV-AC-21105 a deferral of tenant B covers nothing of tenant A and no call writes a row`() {
        seed()
        deferral(tenantB, "E-001", "DOCUMENT", "doc-1")
        ids(drain(tenantA, both, deferred = false)) shouldContainExactly listOf(id(3), id(4), id(2), id(1), id(5))
        drain(tenantA, both, deferred = true) shouldBe emptyList()
        val before = count("select count(*) from console_bff.exception")
        drain(tenantA, both)
        count("select count(*) from console_bff.exception") shouldBe before
        count("select count(*) from console_bff.deferral where tenant_id = '$tenantA'") shouldBe 0
    }
}
