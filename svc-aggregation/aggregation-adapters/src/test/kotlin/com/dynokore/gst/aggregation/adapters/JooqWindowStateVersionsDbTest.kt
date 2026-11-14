package com.dynokore.gst.aggregation.adapters

import com.dynokore.gst.aggregation.adapters.events.WindowChangedOutbox
import com.dynokore.gst.aggregation.adapters.persistence.JooqWindowStateVersions
import com.dynokore.gst.aggregation.application.OpenWindow
import com.dynokore.gst.aggregation.application.WindowChange
import com.dynokore.gst.aggregation.domain.AggregateType
import com.dynokore.gst.aggregation.domain.AggregationWindow
import com.dynokore.gst.aggregation.domain.WindowPart
import com.dynokore.gst.aggregation.domain.WindowState
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.util.UUID

/** The counter of V9__window_state_version.sql behind the outbox adapter, over a migrated database (CS-19). */
class JooqWindowStateVersionsDbTest {
    private val mapper = ObjectMapper()
    private val q2 = WindowPart(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30), "2026-Q2")
    private val q3 = WindowPart(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30), "2026-Q2")
    private val window =
        OpenWindow(
            "E-1",
            "BU-1",
            AggregateType.PCP,
            AggregationWindow(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 9, 30)),
        )

    // one runtime-role connection; the block appends a CLOSED event and gets its stateVersion
    private fun <T> withAppender(block: ((TenantId, WindowPart) -> Long) -> T): T =
        AggregationDb.connect(AggregationDb.RUNTIME, AggregationDb.RUNTIME_PASSWORD).use { connection ->
            connection.autoCommit = false
            var bound = TenantId(UUID.randomUUID())
            val runner =
                object : TransactionRunner {
                    override fun <R> inTransaction(block: () -> R): R {
                        SignedBindings.bind(connection, bound.value)
                        return block()
                    }
                }
            val tenants =
                object : TenantContext {
                    override fun current() = bound

                    override fun currentActor(): String = SignedBindings.ACTOR

                    override fun <R> bind(
                        tenant: TenantId,
                        actor: String,
                        block: () -> R,
                    ): R = block()
                }
            val seen = mutableListOf<String>()
            val outbox =
                object : OutboxWriter {
                    override fun append(
                        events: List<OutboundEvent>,
                        context: EventContext,
                    ): List<Envelope> {
                        events.forEach { seen += it.dataJson }
                        return emptyList()
                    }
                }
            val publisher =
                WindowChangedOutbox(
                    outbox,
                    tenants,
                    JooqWindowStateVersions(DSL.using(connection, SQLDialect.POSTGRES), runner),
                )
            val append = { tenant: TenantId, part: WindowPart ->
                bound = tenant
                publisher.changed(WindowChange(window, part, WindowState.CLOSED, UUID.randomUUID()))
                mapper.readTree(seen.last())["stateVersion"].asLong()
            }
            block(append).also { connection.commit() }
        }

    @Test
    fun `INV-AC-22211 the counter starts at 1 and rises by one per key, per part and per tenant`() {
        val t1 = TenantId(UUID.randomUUID())
        val t2 = TenantId(UUID.randomUUID())
        withAppender { append ->
            append(t1, q2) shouldBe 1L
            append(t1, q2) shouldBe 2L
            append(t1, q3) shouldBe 1L
            append(t2, q2) shouldBe 1L
        }
    }

    @Test
    fun `INV-AC-22211 a rolled-back append does not keep its counter row`() {
        val t1 = TenantId(UUID.randomUUID())
        AggregationDb.connect(AggregationDb.RUNTIME, AggregationDb.RUNTIME_PASSWORD).use { connection ->
            connection.autoCommit = false
            val runner =
                object : TransactionRunner {
                    override fun <R> inTransaction(block: () -> R): R {
                        SignedBindings.bind(connection, t1.value)
                        return block()
                    }
                }
            val versions = JooqWindowStateVersions(DSL.using(connection, SQLDialect.POSTGRES), runner)
            val from = q2.start
            val to = q2.end
            versions.next(t1, "E-1", "2026-Q2", from, to) shouldBe 1L
            connection.commit()
            versions.next(t1, "E-1", "2026-Q2", from, to) shouldBe 2L
            connection.rollback()
            versions.next(t1, "E-1", "2026-Q2", from, to) shouldBe 2L
            connection.commit()
        }
    }

    @Test
    fun `INV-AC-22211 no counter row is readable through another tenant`() {
        val t1 = TenantId(UUID.randomUUID())
        val other = UUID.randomUUID()
        withAppender { append -> append(t1, q2) shouldBe 1L }
        AggregationDb.connect(AggregationDb.RUNTIME, AggregationDb.RUNTIME_PASSWORD).use { connection ->
            connection.autoCommit = false
            SignedBindings.bind(connection, other)
            DSL
                .using(connection, SQLDialect.POSTGRES)
                .fetchCount(DSL.table(DSL.name("aggregation", "window_state_version"))) shouldBe 0
            connection.rollback()
        }
    }
}
