package com.dynokore.gst.consoleBff.application.exceptions

import com.dynokore.gst.consoleBff.domain.badge.ItemState
import com.dynokore.gst.consoleBff.domain.exceptions.ExceptionCursor
import com.dynokore.gst.consoleBff.domain.exceptions.ExceptionRow
import com.dynokore.gst.consoleBff.domain.exceptions.ExceptionSortKey
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class ListExceptionsTest {
    private data class Call(
        val tenantId: String,
        val entityRefs: Set<String>,
        val state: ItemState?,
        val deferred: Boolean,
        val after: ExceptionSortKey?,
        val limit: Int,
    )

    /** One in-memory class implements both ports: compile-time conformance of the port shapes. */
    private class Fake(
        val scopeOf: Set<String>,
        val rows: List<QueuedException>,
    ) : ExceptionQueue,
        EntityScope {
        val calls = mutableListOf<Call>()

        override fun entitiesOf(
            tenantId: String,
            principalId: String,
        ) = scopeOf

        override fun page(
            tenantId: String,
            entityRefs: Set<String>,
            state: ItemState?,
            deferred: Boolean,
            after: ExceptionSortKey?,
            limit: Int,
        ): List<QueuedException> {
            calls += Call(tenantId, entityRefs, state, deferred, after, limit)
            val scoped = rows.filter { it.row.entityRef in entityRefs && (state == null || it.row.state == state) }
            val start = if (after == null) 0 else scoped.indexOfFirst { keyOf(it) == after } + 1
            return scoped.drop(start).take(limit)
        }

        private fun keyOf(q: QueuedException) =
            ExceptionSortKey(q.dueDate, q.row.raisedAt, q.row.exceptionId.toString())
    }

    private fun row(
        n: Int,
        entity: String,
        due: String?,
    ) = QueuedException(
        ExceptionRow(
            "T1",
            UUID.nameUUIDFromBytes("x$n".toByteArray()),
            entity,
            "doc-$n",
            "MDM-001",
            "Finance",
            "FIX",
            ItemState.OPEN,
            Instant.parse("2026-11-01T08:0$n:00Z"),
            "",
            n.toLong(),
        ),
        due?.let(LocalDate::parse),
    )

    private val x1 = row(1, "E-001", "2026-11-21")
    private val x2 = row(2, "E-002", "2026-11-21")
    private val x3 = row(3, "E-001", null)

    private fun fake(scope: Set<String> = setOf("E-001", "E-002")) = Fake(scope, listOf(x1, x2, x3))

    private fun use(f: Fake) = ListExceptions(f, f, 500)

    private fun query(
        entityRef: String? = null,
        state: ItemState? = null,
        deferred: Boolean = false,
        limit: Int = 2,
        cursor: String? = null,
    ) = ListExceptionsQuery("T1", "P1", entityRef, state, deferred, limit, cursor)

    @Test
    fun `INV-AC-21103 first page asks limit plus one, returns limit rows and a cursor of the last returned row`() {
        val f = fake()
        val result = use(f).list(query()) as ListExceptionsResult.Listed
        f.calls.single() shouldBe Call("T1", setOf("E-001", "E-002"), null, false, null, 3)
        result.items shouldBe listOf(x1, x2)
        ExceptionCursor.decode(result.nextCursor!!) shouldBe
            ExceptionSortKey(LocalDate.parse("2026-11-21"), x2.row.raisedAt, x2.row.exceptionId.toString())
    }

    @Test
    fun `INV-AC-21103 second page passes the decoded key, returns the rest without a due date guess and no cursor`() {
        val f = fake()
        val first = use(f).list(query()) as ListExceptionsResult.Listed
        val second = use(f).list(query(cursor = first.nextCursor)) as ListExceptionsResult.Listed
        f.calls.last().after shouldBe ExceptionCursor.decode(first.nextCursor!!)
        second.items shouldBe listOf(x3)
        second.items.single().dueDate shouldBe null
        second.nextCursor shouldBe null
    }

    @Test
    fun `INV-AC-21103 entity outside the scope is OutOfScope and the queue is not called`() {
        val f = fake()
        use(f).list(query(entityRef = "E-003")) shouldBe ListExceptionsResult.OutOfScope("E-003")
        f.calls shouldBe emptyList()
    }

    @Test
    fun `INV-AC-21103 entity in scope and state OPEN narrow the queue call`() {
        val f = fake()
        use(f).list(query(entityRef = "E-002", state = ItemState.OPEN))
        f.calls.single().entityRefs shouldBe setOf("E-002")
        f.calls.single().state shouldBe ItemState.OPEN
    }

    @Test
    fun `INV-AC-21103 malformed cursor is BadCursor and the queue is not called`() {
        val f = fake()
        use(f).list(query(cursor = "not-a-cursor")) shouldBe ListExceptionsResult.BadCursor
        f.calls shouldBe emptyList()
    }

    @Test
    fun `INV-AC-21103 limit below one or above the maximum is BadLimit and the queue is not called`() {
        val f = fake()
        use(f).list(query(limit = 0)) shouldBe ListExceptionsResult.BadLimit
        use(f).list(query(limit = 501)) shouldBe ListExceptionsResult.BadLimit
        f.calls shouldBe emptyList()
        (use(f).list(query(limit = 500)) is ListExceptionsResult.Listed) shouldBe true
    }

    @Test
    fun `INV-AC-21103 deferred is handed to the queue as given`() {
        val f = fake()
        val result = use(f).list(query(deferred = true, limit = 10)) as ListExceptionsResult.Listed
        f.calls.single().deferred shouldBe true
        result.items shouldBe listOf(x1, x2, x3)
        use(f).list(query())
        f.calls.last().deferred shouldBe false
    }

    @Test
    fun `INV-AC-21103 empty scope gives an empty Listed and the queue is not called`() {
        val f = fake(emptySet())
        use(f).list(query()) shouldBe ListExceptionsResult.Listed(emptyList(), null)
        f.calls shouldBe emptyList()
    }
}
