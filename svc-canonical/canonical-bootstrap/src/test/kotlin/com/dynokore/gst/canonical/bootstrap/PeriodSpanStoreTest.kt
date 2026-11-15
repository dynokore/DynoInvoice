package com.dynokore.gst.canonical.bootstrap

import com.dynokore.gst.canonical.adapters.CanonicalDb
import com.dynokore.gst.canonical.adapters.SignedBindings
import com.dynokore.gst.canonical.adapters.persistence.JooqPeriodSpans
import com.dynokore.gst.canonical.adapters.persistence.PeriodSpanSet
import com.dynokore.gst.canonical.domain.dating.PeriodSpan
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import java.nio.file.Path
import java.time.LocalDate
import java.util.UUID

/**
 * The period span tables against a real database (P109): the jOOQ store in the full TPL-18 context, as the
 * `canonical_runtime` login bound to a tenant (INV-REQ-479, INV-Q-21406 A).
 */
class PeriodSpanStoreTest {
    private val actor = SignedBindings.ACTOR
    private val q1 = PeriodSpan("2026-Q1", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31))
    private val q2 = PeriodSpan("2026-Q2", LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30))
    private val q9 = PeriodSpan("2026-Q9", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30))
    private val h2 = PeriodSpan("2026-H2", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 12, 31))

    private class Boundary(
        context: ApplicationContext,
    ) {
        val scope: TenantScope = context.getBean(TenantScope::class.java)
        val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val store = JooqPeriodSpans(context.getBean(DSLContext::class.java))
    }

    private fun run(
        dir: Path,
        block: (Boundary) -> Unit,
    ) {
        CanonicalContext.online(dir).run { block(Boundary(it)) }
    }

    private fun Boundary.applyAs(
        tenant: UUID,
        version: Long,
        vararg spans: PeriodSpan,
        entity: String = "ENT-1",
    ): Boolean =
        scope.bind(TenantId(tenant), actor) {
            runner.inTransaction { store.apply(PeriodSpanSet(tenant, entity, version, spans.toList())) }
        }

    private fun Boundary.spansAs(
        session: UUID,
        tenant: UUID,
        entity: String = "ENT-1",
    ): List<PeriodSpan> =
        scope.bind(TenantId(session), actor) { runner.inTransaction { store.spansOf(tenant, entity) } }

    @Test
    fun `INV-AC-21428 a newer version replaces the whole set, an equal or older one is ignored, answers in start order`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val t1 = UUID.randomUUID()
        // the later span is given first: the answer must still be earliest start first
        b.applyAs(t1, 3, q2, q1) shouldBe true
        b.spansAs(t1, t1) shouldBe listOf(q1, q2)

        b.applyAs(t1, 3, q9) shouldBe false
        b.spansAs(t1, t1) shouldBe listOf(q1, q2)

        b.applyAs(t1, 2, q9, h2) shouldBe false
        b.spansAs(t1, t1) shouldBe listOf(q1, q2)

        b.applyAs(t1, 4, h2) shouldBe true
        b.spansAs(t1, t1) shouldBe listOf(h2)
    }

    @Test
    fun `INV-AC-21428 spans with the same start are answered by period reference`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val t1 = UUID.randomUUID()
        b.applyAs(t1, 1, h2, q9) shouldBe true
        b.spansAs(t1, t1) shouldBe listOf(h2, q9)

        // the start decides before the reference: "Z" starts earlier than "A"
        val z = PeriodSpan("Z", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31))
        val a = PeriodSpan("A", LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30))
        b.applyAs(t1, 2, a, z) shouldBe true
        b.spansAs(t1, t1) shouldBe listOf(z, a)
    }

    @Test
    fun `INV-AC-21428 another entity and another tenant see no span of the entity`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val t1 = UUID.randomUUID()
        val t2 = UUID.randomUUID()
        b.applyAs(t1, 3, q1, q2) shouldBe true

        b.spansAs(t1, t1, "ENT-2") shouldBe emptyList()
        b.spansAs(t2, t2) shouldBe emptyList()
        // the same entity reference under the other tenant is independent, also in its version
        b.applyAs(t2, 1, q1) shouldBe true
        b.spansAs(t2, t2) shouldBe listOf(q1)
        b.spansAs(t1, t1) shouldBe listOf(q1, q2)
        // a session bound to T2 reads nothing of T1 even when it asks for T1
        b.spansAs(t2, t1) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-21428 a span ending before its start is refused by the database and the stored set stays`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val t1 = UUID.randomUUID()
        b.applyAs(t1, 3, q1, q2) shouldBe true
        // PeriodSpan itself rejects end before start, so the replacement is written as the runtime login in plain SQL
        val sqlState =
            CanonicalDb.connect(CanonicalDb.RUNTIME, CanonicalDb.RUNTIME_PASSWORD).use { connection ->
                connection.autoCommit = false
                SignedBindings.bind(connection, t1)
                try {
                    connection.createStatement().use { st ->
                        st.executeUpdate(
                            "update canonical.period_span_version set version = 4 where entity_ref = 'ENT-1'",
                        )
                        st.executeUpdate("delete from canonical.period_span where entity_ref = 'ENT-1'")
                        st.executeUpdate(
                            "insert into canonical.period_span values ('$t1', 'ENT-1', 'BAD', date '2026-04-01', date '2026-03-31')",
                        )
                    }
                    null
                } catch (refused: java.sql.SQLException) {
                    refused.sqlState
                } finally {
                    connection.rollback()
                }
            }
        sqlState shouldBe "23514"
        b.spansAs(t1, t1) shouldBe listOf(q1, q2)
        b.applyAs(t1, 4, h2) shouldBe true
    }
}
