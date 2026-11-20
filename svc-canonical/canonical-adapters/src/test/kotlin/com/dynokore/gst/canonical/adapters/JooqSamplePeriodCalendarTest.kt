package com.dynokore.gst.canonical.adapters

import com.dynokore.gst.canonical.adapters.persistence.JooqPeriodSpans
import com.dynokore.gst.canonical.adapters.persistence.JooqSamplePeriodCalendar
import com.dynokore.gst.canonical.adapters.persistence.PeriodSpanSet
import com.dynokore.gst.canonical.application.sample.SamplePeriods
import com.dynokore.gst.canonical.domain.dating.PeriodSpan
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** INV-AC-25430: the sample period calendar over `canonical.period_span`, bound to one tenant (INV-REQ-772). */
class JooqSamplePeriodCalendarTest {
    private val q1 = PeriodSpan("2026-Q1", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31))
    private val q2 = PeriodSpan("2026-Q2", LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30))
    private val q3 = PeriodSpan("2026-Q3", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30))

    // The same reference as q2 but earlier in start order, so a missing filter changes the answer.
    private val other = PeriodSpan("2026-Q2", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31))

    /** Runs [block] as the runtime login in one transaction bound to [tenant]. */
    private fun <T> asTenant(
        tenant: UUID,
        block: (org.jooq.DSLContext) -> T,
    ): T =
        CanonicalDb.connect(CanonicalDb.RUNTIME, CanonicalDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, tenant)
            val result = block(DSL.using(c, SQLDialect.POSTGRES))
            c.commit()
            result
        }

    private fun store(
        tenant: UUID,
        entity: String,
        vararg spans: PeriodSpan,
    ) = asTenant(tenant) { JooqPeriodSpans(it).apply(PeriodSpanSet(tenant, entity, 1, spans.toList())) }

    private fun spans(
        tenant: UUID,
        entity: String,
        first: String,
        last: String,
        session: UUID = tenant,
    ): SamplePeriods = asTenant(session) { JooqSamplePeriodCalendar(it).spans(tenant, entity, first, last) }

    @Test
    fun `INV-AC-25430 1 a window of two of three stored spans answers exactly those two in start order`() {
        val t = UUID.randomUUID()
        store(t, "E-1", q3, q1, q2)
        spans(t, "E-1", "2026-Q2", "2026-Q3") shouldBe SamplePeriods.Known(listOf(q2, q3))
        spans(t, "E-1", "2026-Q1", "2026-Q3") shouldBe SamplePeriods.Known(listOf(q1, q2, q3))
        spans(t, "E-1", "2026-Q2", "2026-Q2") shouldBe SamplePeriods.Known(listOf(q2))
    }

    @Test
    fun `INV-AC-25430 2 a reference not among the spans answers Unknown`() {
        val t = UUID.randomUUID()
        store(t, "E-1", q1, q2)
        spans(t, "E-1", "2026-Q1", "2026-Q9") shouldBe SamplePeriods.Unknown
        spans(t, "E-1", "2025-Q4", "2026-Q2") shouldBe SamplePeriods.Unknown
    }

    @Test
    fun `INV-AC-25430 3 an entity with no spans answers Unknown`() {
        val t = UUID.randomUUID()
        store(t, "E-1", q1)
        spans(t, "E-2", "2026-Q1", "2026-Q1") shouldBe SamplePeriods.Unknown
    }

    @Test
    fun `INV-AC-25430 4 spans of another tenant or another entity with the same references are never returned`() {
        val t1 = UUID.randomUUID()
        val t2 = UUID.randomUUID()
        store(t1, "E-1", q1, q2)
        store(t1, "E-2", other)
        store(t2, "E-1", other)
        spans(t1, "E-1", "2026-Q1", "2026-Q2") shouldBe SamplePeriods.Known(listOf(q1, q2))
        spans(t1, "E-2", "2026-Q2", "2026-Q2") shouldBe SamplePeriods.Known(listOf(other))
        spans(t2, "E-1", "2026-Q2", "2026-Q2") shouldBe SamplePeriods.Known(listOf(other))
        // the tenant filter by itself: asking for t1's spans while bound to t2 sees nothing
        spans(t1, "E-1", "2026-Q1", "2026-Q2", session = t2) shouldBe SamplePeriods.Unknown
        // the tenant filter by itself, without row-level security (the table owner of the database sees every row)
        val unbound =
            CanonicalDb.superuser().use { c ->
                JooqSamplePeriodCalendar(DSL.using(c, SQLDialect.POSTGRES)).spans(t1, "E-1", "2026-Q1", "2026-Q2")
            }
        unbound shouldBe SamplePeriods.Known(listOf(q1, q2))
        // the entity filter by itself: t1 holds a Q1 only for E-1
        spans(t1, "E-2", "2026-Q1", "2026-Q2") shouldBe SamplePeriods.Unknown
    }

    @Test
    fun `INV-AC-25430 5 a last reference earlier than the first answers Unknown`() {
        val t = UUID.randomUUID()
        store(t, "E-1", q1, q2, q3)
        spans(t, "E-1", "2026-Q3", "2026-Q1") shouldBe SamplePeriods.Unknown
    }
}
