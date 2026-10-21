package com.dynokore.gst.payload.bootstrap

import com.dynokore.gst.payload.adapters.persistence.jooq.tables.Outbox
import com.dynokore.gst.payload.application.CapabilityRegistry
import com.dynokore.gst.payload.domain.CapabilityKey
import com.dynokore.gst.payload.domain.CapabilityState
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
 * The service boundary against a real database (P109): the full TPL-18 context over the `payload_runtime` login.
 * Events leave through the outbox in the command transaction and only the catalogued types (INV-REQ-815), and a
 * tenant reads only its own rows (CS-19).
 */
class PayloadBoundaryDbTest {
    private val actor = "svc:payload-svc"

    private class Boundary(
        context: ApplicationContext,
    ) {
        val scope: TenantScope = context.getBean(TenantScope::class.java)
        val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        val outbox: OutboxWriter = context.getBean(OutboxWriter::class.java)
        val capabilities: CapabilityRegistry = context.getBean(CapabilityRegistry::class.java)
    }

    private fun run(
        dir: Path,
        block: (Boundary) -> Unit,
    ) {
        PayloadContext.online(dir).run { context -> block(Boundary(context)) }
    }

    @Test
    fun `a tenant reads none of the outbox rows of another tenant through the service boundary`(
        @TempDir dir: Path,
    ) = run(dir) { boundary ->
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        boundary.emit(a, PayloadEvents.entries.first().eventType)
        boundary.emit(b, PayloadEvents.entries.first().eventType)
        boundary.outboxRows(a) shouldBe 1
        boundary.outboxRows(b) shouldBe 1
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

    @Test
    fun `the emission guard rejects an event type not catalogued for payload and writes nothing`(
        @TempDir dir: Path,
    ) = run(dir) { boundary ->
        val tenant = UUID.randomUUID()
        shouldThrow<EmissionNotPermitted> { boundary.emit(tenant, "com.dynokore.gst.payload.Invented.v1") }
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
        PayloadEvents.entries.forEach { boundary.emit(tenant, it.eventType) }
        boundary.outboxRows(tenant) shouldBe PayloadEvents.entries.size
    }

    @Test
    fun `INV-AC-1669 serialisation is not marked specified while no binding table exists`(
        @TempDir dir: Path,
    ) = run(dir) { boundary ->
        val state =
            boundary.scope.bind(TenantId(UUID.randomUUID()), actor) {
                boundary.runner.inTransaction { boundary.capabilities.state(CapabilityKey.SERIALISATION) }
            }
        state shouldBe CapabilityState.NOT_SPECIFIED
    }
}
