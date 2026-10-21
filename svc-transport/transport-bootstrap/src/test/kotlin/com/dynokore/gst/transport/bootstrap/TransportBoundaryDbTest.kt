package com.dynokore.gst.transport.bootstrap

import com.dynokore.gst.transport.adapters.persistence.jooq.tables.Outbox
import com.dynokore.gst.transport.adapters.persistence.jooq.tables.SubmissionAttempt.Companion.SUBMISSION_ATTEMPT
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
import java.nio.file.Path
import java.util.UUID

/**
 * The service boundary against a real database (P109): events leave through the outbox in the command transaction
 * and only the catalogued types (INV-REQ-815), and a tenant reads only its own rows (CS-19).
 */
class TransportBoundaryDbTest {
    private val actor = "svc:transport-svc"

    private class Boundary(
        context: org.springframework.context.ApplicationContext,
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
        TransportContext.online(dir).run { context -> block(Boundary(context)) }
    }

    private fun Boundary.attempts(tenant: UUID): Int =
        scope.bind(TenantId(tenant), actor) { runner.inTransaction { dsl.fetchCount(SUBMISSION_ATTEMPT) } }

    private fun Boundary.record(tenant: UUID) =
        scope.bind(TenantId(tenant), actor) {
            runner.inTransaction {
                dsl
                    .insertInto(SUBMISSION_ATTEMPT)
                    .set(SUBMISSION_ATTEMPT.ID, UUID.randomUUID())
                    .set(SUBMISSION_ATTEMPT.TENANT_ID, tenant)
                    .set(SUBMISSION_ATTEMPT.ENTITY_REF, "E-001")
                    .set(SUBMISSION_ATTEMPT.DOC_UUID, UUID.randomUUID())
                    .set(SUBMISSION_ATTEMPT.VERSION, 1)
                    .set(SUBMISSION_ATTEMPT.ADAPTER_ID, "primary")
                    .set(SUBMISSION_ATTEMPT.STARTED_AT, java.time.OffsetDateTime.now())
                    .execute()
            }
        }

    @Test
    fun `a tenant reads none of the rows of another tenant through the service boundary`(
        @TempDir dir: Path,
    ) = run(dir) { boundary ->
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        boundary.record(a)
        boundary.record(b)
        boundary.record(b)
        boundary.attempts(a) shouldBe 1
        boundary.attempts(b) shouldBe 2
        boundary.attempts(UUID.randomUUID()) shouldBe 0
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
    fun `the emission guard rejects an event type not catalogued for transport and writes nothing`(
        @TempDir dir: Path,
    ) = run(dir) { boundary ->
        val tenant = UUID.randomUUID()
        shouldThrow<EmissionNotPermitted> { boundary.emit(tenant, "com.dynokore.gst.transport.Invented.v1") }
        shouldThrow<EmissionNotPermitted> {
            boundary.emit(tenant, "com.dynokore.gst.canonical.CanonicalVersionCreated.v1")
        }
        // the scaffold placeholder of the contract is not catalogued either
        shouldThrow<EmissionNotPermitted> { boundary.emit(tenant, "com.dynokore.gst.transport.ExampleHappened.v1") }
        boundary.outboxRows(tenant) shouldBe 0
    }

    @Test
    fun `a catalogued event leaves through the outbox in the command transaction`(
        @TempDir dir: Path,
    ) = run(dir) { boundary ->
        val tenant = UUID.randomUUID()
        TransportEvents.entries.forEach { boundary.emit(tenant, it.eventType) }
        boundary.outboxRows(tenant) shouldBe TransportEvents.entries.size
    }
}
