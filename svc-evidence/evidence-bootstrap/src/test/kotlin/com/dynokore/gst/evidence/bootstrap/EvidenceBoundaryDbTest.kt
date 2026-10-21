package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.adapters.EvidenceDb
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.Outbox
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
 * The service boundary against a real database (P109): the full TPL-18 context over the `evidence_runtime` login.
 * Events leave through the outbox in the command
 * transaction and only the catalogued types (INV-REQ-815), and a tenant reads only its own rows (CS-19).
 */
class EvidenceBoundaryDbTest {
    private val actor = "svc:evidence-svc"

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
        EvidenceContext.online(dir).run { context -> block(Boundary(context)) }
    }

    private fun Boundary.emit(
        tenant: UUID,
        type: String,
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
        }
    }

    private fun Boundary.outboxRows(tenant: UUID): Int =
        scope.bind(TenantId(tenant), actor) { runner.inTransaction { dsl.fetchCount(Outbox.OUTBOX) } }

    private fun Boundary.outboxRecords(tenant: UUID): List<Map<String, Any?>> =
        scope.bind(TenantId(tenant), actor) {
            runner.inTransaction {
                dsl
                    .selectFrom(
                        Outbox.OUTBOX,
                    ).orderBy(Outbox.OUTBOX.SEQ)
                    .fetch()
                    .map { it.intoMap() }
            }
        }

    /**
     * INV-AC-1711 (INV-REQ-636): the part evidence-svc produces. Events and payload records reach the evidence store in
     * the command transaction; archiving or purging an operational store (here the scratch schema `operational_probe`,
     * truncated and dropped) leaves every one retrievable through the service boundary. The lookups and the audit pack
     * that return them belong to later tasks (operations.tsv); coordinator rulings INV-Q-4876 and INV-Q-4896.
     */
    @Test
    fun `INV-AC-1711 purging an operational store leaves every evidence record retrievable`(
        @TempDir dir: Path,
    ) = run(dir) { boundary ->
        val tenant = UUID.randomUUID()
        EvidenceEvents.entries.forEach { boundary.emit(tenant, it.eventType) }
        val before = boundary.outboxRecords(tenant)
        before.size shouldBe EvidenceEvents.entries.size
        EvidenceDb.superuser().use { connection ->
            connection.createStatement().use {
                it.execute("create schema operational_probe")
                it.execute("create table operational_probe.item (id uuid primary key, tenant_id uuid not null)")
                it.execute("insert into operational_probe.item values (gen_random_uuid(), '$tenant')")
                it.execute("truncate operational_probe.item")
                it.execute("drop schema operational_probe cascade")
            }
        }
        boundary.outboxRecords(tenant) shouldBe before
    }

    @Test
    fun `the emission guard rejects an event type not catalogued for evidence and writes nothing`(
        @TempDir dir: Path,
    ) = run(dir) { boundary ->
        val tenant = UUID.randomUUID()
        shouldThrow<EmissionNotPermitted> { boundary.emit(tenant, "com.dynokore.gst.evidence.Invented.v1") }
        shouldThrow<EmissionNotPermitted> {
            boundary.emit(
                tenant,
                "com.dynokore.gst.evidence.ExampleHappened.v1",
            )
        }
        boundary.outboxRows(tenant) shouldBe 0
    }

    @Test
    fun `a catalogued event leaves through the outbox in the command transaction`(
        @TempDir dir: Path,
    ) = run(dir) { boundary ->
        val tenant = UUID.randomUUID()
        EvidenceEvents.entries.forEach { boundary.emit(tenant, it.eventType) }
        boundary.outboxRows(tenant) shouldBe EvidenceEvents.entries.size
    }

    @Test
    fun `a tenant reads none of the rows of another tenant through the service boundary`(
        @TempDir dir: Path,
    ) = run(dir) { boundary ->
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        boundary.emit(a, EvidenceEvents.entries.first().eventType)
        boundary.emit(b, EvidenceEvents.entries.first().eventType)
        boundary.outboxRows(a) shouldBe 1
        boundary.outboxRows(b) shouldBe 1
    }
}
