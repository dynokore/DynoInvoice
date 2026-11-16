package com.dynokore.gst.canonical.bootstrap

import com.dynokore.gst.canonical.adapters.CanonicalDb
import com.dynokore.gst.canonical.adapters.SignedBindings
import com.dynokore.gst.canonical.adapters.events.PeriodRecordsReader
import com.dynokore.gst.canonical.adapters.persistence.JooqPeriodSpans
import com.dynokore.gst.canonical.adapters.persistence.PeriodSpanSet
import com.dynokore.gst.canonical.domain.dating.PeriodSpan
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import java.nio.file.Path
import java.time.LocalDate
import java.util.UUID

/** INV-AC-21535: the period span store keeps the periodId of each period and answers it by period reference. */
class PeriodSpanPeriodIdTest {
    private val actor = SignedBindings.ACTOR
    private val u1 = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001")
    private val u2 = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000002")
    private val u3 = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000003")
    private val p1 = PeriodSpan("P1", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31))
    private val p2 = PeriodSpan("P2", LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30))

    private class Boundary(
        context: ApplicationContext,
    ) {
        val scope: TenantScope = context.getBean(TenantScope::class.java)
        val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        val store = JooqPeriodSpans(dsl)
        val reader = PeriodRecordsReader()
    }

    private fun item(
        ref: String,
        start: String,
        end: String,
        id: String?,
    ) = "{\"periodRef\":\"$ref\",\"periodStart\":\"$start\",\"periodEnd\":\"$end\"" +
        (if (id != null) ",\"periodId\":$id" else "") + "}"

    private fun event(
        version: Int,
        id1: String?,
        id2: String?,
    ) = "{\"entityRef\":\"E-001\",\"version\":$version,\"periods\":[" +
        item("P1", "2026-01-01", "2026-03-31", id1) + "," + item("P2", "2026-04-01", "2026-06-30", id2) + "]}"

    private fun Boundary.feed(
        tenant: UUID,
        data: String,
    ): Boolean =
        scope.bind(TenantId(tenant), actor) {
            runner.inTransaction { store.apply(reader.read(data, TenantId(tenant))) }
        }

    private fun Boundary.idOf(
        session: UUID,
        tenant: UUID,
        ref: String,
    ): UUID? = scope.bind(TenantId(session), actor) { runner.inTransaction { store.periodIdOf(tenant, "E-001", ref) } }

    private fun Boundary.spans(tenant: UUID): List<PeriodSpan> =
        scope.bind(TenantId(tenant), actor) { runner.inTransaction { store.spansOf(tenant, "E-001") } }

    private fun run(
        dir: Path,
        block: (Boundary) -> Unit,
    ) {
        CanonicalContext.online(dir).run { block(Boundary(it)) }
    }

    private fun q(id: UUID) = "\"$id\""

    @Test
    fun `INV-AC-21535 F1 the ids of the first set are stored per period`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val t1 = UUID.randomUUID()
        b.feed(t1, event(5, q(u1), q(u2))) shouldBe true
        b.idOf(t1, t1, "P1") shouldBe u1
        b.idOf(t1, t1, "P2") shouldBe u2
        b.spans(t1) shouldBe listOf(p1, p2)
    }

    @Test
    fun `INV-AC-21535 F2 a newer set replaces the id of P2 and keeps P1`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val t1 = UUID.randomUUID()
        b.feed(t1, event(5, q(u1), q(u2))) shouldBe true
        b.feed(t1, event(6, q(u1), q(u3))) shouldBe true
        b.idOf(t1, t1, "P2") shouldBe u3
        b.idOf(t1, t1, "P1") shouldBe u1
        b.spans(t1) shouldBe listOf(p1, p2)
    }

    @Test
    fun `INV-AC-21535 F3 a period of the newest set without an id has none`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val t1 = UUID.randomUUID()
        b.feed(t1, event(5, q(u1), q(u2))) shouldBe true
        b.feed(t1, event(6, q(u1), q(u3))) shouldBe true
        b.feed(t1, event(7, null, q(u3))) shouldBe true
        b.idOf(t1, t1, "P1") shouldBe null
        b.idOf(t1, t1, "P2") shouldBe u3
        b.spans(t1) shouldBe listOf(p1, p2)
    }

    @Test
    fun `INV-AC-21535 F4 a periodId that is not a uuid is dead-lettered and changes nothing`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val t1 = UUID.randomUUID()
        b.feed(t1, event(7, null, q(u3))) shouldBe true
        assertThrows<ContractViolation> { b.feed(t1, event(8, "\"7\"", q(u3))) }
        b.idOf(t1, t1, "P1") shouldBe null
        b.idOf(t1, t1, "P2") shouldBe u3
        b.spans(t1) shouldBe listOf(p1, p2)
    }

    @Test
    fun `INV-AC-21535 F4 look-alike ids are contract violations`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val t1 = UUID.randomUUID()
        listOf("7", "\"${u1.toString().uppercase()}\"", "\"${u1.toString().replace("-", "")}\"", "\"\"", "[]").forEach {
            assertThrows<ContractViolation> { b.feed(t1, event(9, it, null)) }
        }
        b.spans(t1) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-21535 reads for an unknown period and for another tenant are null`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val t1 = UUID.randomUUID()
        val t2 = UUID.randomUUID()
        b.feed(t1, event(5, q(u1), q(u2))) shouldBe true
        b.idOf(t1, t1, "P9") shouldBe null
        b.idOf(t2, t1, "P1") shouldBe null
        b.idOf(t2, t2, "P1") shouldBe null
        b.feed(t2, event(1, q(u3), null)) shouldBe true
        b.idOf(t2, t2, "P1") shouldBe u3
        b.idOf(t1, t1, "P1") shouldBe u1
    }

    @Test
    fun `INV-AC-21535 the column has no default and a span stored without an id holds NULL`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val t1 = UUID.randomUUID()
        b.feed(t1, event(1, null, null)) shouldBe true
        CanonicalDb.superuser().use { c ->
            val rs =
                c.createStatement().executeQuery(
                    "select column_default, is_nullable from information_schema.columns " +
                        "where table_schema = 'canonical' and table_name = 'period_span' and column_name = 'period_id'",
                )
            rs.next() shouldBe true
            rs.getString(1) shouldBe null
            rs.getString(2) shouldBe "YES"
            val rows =
                c.createStatement().executeQuery(
                    "select count(*) from canonical.period_span where tenant_id = '$t1' and period_id is null",
                )
            rows.next() shouldBe true
            rows.getInt(1) shouldBe 2
        }
    }

    @Test
    fun `INV-AC-21535 F5 without row security periodIdOf answers the id of its own tenant only`() {
        val t1 = UUID.randomUUID()
        val t2 = UUID.randomUUID()
        CanonicalDb.superuser().use { c ->
            listOf(t1 to u1, t2 to u2).forEach { (tenant, id) ->
                c.createStatement().executeUpdate(
                    "insert into canonical.period_span (tenant_id, entity_ref, period_ref, period_start, period_end, " +
                        "period_id) values ('$tenant', 'E-001', 'P1', '2026-01-01', '2026-03-31', '$id')",
                )
            }
            val bare = JooqPeriodSpans(DSL.using(c, SQLDialect.POSTGRES))
            bare.periodIdOf(t1, "E-001", "P1") shouldBe u1
            bare.periodIdOf(t2, "E-001", "P1") shouldBe u2
        }
    }

    @Test
    fun `INV-AC-21535 F6 a JSON null periodId is a contract violation and changes nothing`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val t1 = UUID.randomUUID()
        b.feed(t1, event(7, q(u1), q(u3))) shouldBe true
        assertThrows<ContractViolation> { b.feed(t1, event(9, "null", q(u3))) }
        b.idOf(t1, t1, "P1") shouldBe u1
        b.idOf(t1, t1, "P2") shouldBe u3
        b.spans(t1) shouldBe listOf(p1, p2)
    }

    @Test
    fun `INV-AC-21535 a periodIds key that is no span of the set is refused`() {
        val t = UUID.randomUUID()
        assertThrows<IllegalArgumentException> { PeriodSpanSet(t, "E-001", 1, listOf(p1), mapOf("P2" to u1)) }
        PeriodSpanSet(t, "E-001", 1, listOf(p1), mapOf("P1" to u1)).periodIds shouldBe mapOf("P1" to u1)
    }
}
