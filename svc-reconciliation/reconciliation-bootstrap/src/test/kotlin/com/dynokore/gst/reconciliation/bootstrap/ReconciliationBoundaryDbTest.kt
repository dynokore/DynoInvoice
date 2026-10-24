package com.dynokore.gst.reconciliation.bootstrap

import com.dynokore.gst.reconciliation.adapters.persistence.jooq.tables.Outbox
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EmissionNotPermitted
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import java.nio.file.Path
import java.util.UUID

/**
 * The service boundary against a real database (P109): the full TPL-18 context over the `reconciliation_runtime` login.
 * Events leave through the outbox in the command
 * transaction and only the catalogued types (INV-REQ-815), and a tenant reads only its own rows (CS-19).
 */
class ReconciliationBoundaryDbTest {
    private val actor = "svc:reconciliation-svc"

    private class Boundary(
        context: ApplicationContext,
    ) {
        val scope: TenantScope = context.getBean(TenantScope::class.java)
        val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        val outbox: OutboxWriter = context.getBean(OutboxWriter::class.java)
    }

    private fun run(
        dir: Path,
        block: (Boundary) -> Unit,
    ) {
        ReconciliationContext.online(dir).run { context -> block(Boundary(context)) }
    }

    private fun Boundary.emit(
        tenant: UUID,
        type: String,
        failAfter: Boolean = false,
    ) = scope.bind(TenantId(tenant), actor) {
        runner.inTransaction {
            val doc = UUID.randomUUID()
            outbox.append(
                listOf(OutboundEvent(type, "{}")),
                EventContext(
                    TenantId(tenant),
                    EntityRef("E-001"),
                    null,
                    null,
                    doc.toString(),
                    null,
                    CorrelationId("corr-1"),
                    CausationId("cause-1"),
                ),
            )
            check(!failAfter) { "forced failure after the emission" }
        }
    }

    private fun Boundary.outboxRows(tenant: UUID): Int =
        scope.bind(TenantId(tenant), actor) { runner.inTransaction { dsl.fetchCount(Outbox.OUTBOX) } }

    @Test
    fun `the emission guard rejects an event type not catalogued for reconciliation and writes nothing`(
        @TempDir dir: Path,
    ) = run(dir) { boundary ->
        val tenant = UUID.randomUUID()
        shouldThrow<EmissionNotPermitted> { boundary.emit(tenant, "com.dynokore.gst.reconciliation.Invented.v1") }
        shouldThrow<EmissionNotPermitted> {
            boundary.emit(
                tenant,
                "com.dynokore.gst.canonical.CanonicalVersionCreated.v1",
            )
        }
        boundary.outboxRows(tenant) shouldBe 0
    }

    @Test
    fun `a catalogued event leaves through the outbox in the command transaction`(
        @TempDir dir: Path,
    ) = run(dir) { boundary ->
        val tenant = UUID.randomUUID()
        ReconciliationEvents.entries.forEach { boundary.emit(tenant, it.eventType) }
        boundary.outboxRows(tenant) shouldBe ReconciliationEvents.entries.size
    }

    @Test
    fun `a forced failure after the emission leaves no outbox row`(
        @TempDir dir: Path,
    ) = run(dir) { boundary ->
        val tenant = UUID.randomUUID()
        shouldThrow<IllegalStateException> {
            boundary.emit(tenant, ReconciliationEvents.entries.first().eventType, failAfter = true)
        }
        boundary.outboxRows(tenant) shouldBe 0
    }

    @Test
    fun `a tenant reads none of the outbox rows of another tenant through the service boundary`(
        @TempDir dir: Path,
    ) = run(dir) { boundary ->
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        boundary.emit(a, ReconciliationEvents.entries.first().eventType)
        boundary.emit(b, ReconciliationEvents.entries.first().eventType)
        boundary.outboxRows(a) shouldBe 1
        boundary.outboxRows(b) shouldBe 1
    }
}
