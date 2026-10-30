package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Outbox
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Submission
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.WorkQueue
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EmissionNotPermitted
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.Lane
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.outboxandqueue.QueueEntry
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
import com.dynokore.platform.outboxandqueue.WorkQueue as WorkQueuePort

/**
 * The service boundary against a real database (P109): the full TPL-18 context over the `submission_runtime` login.
 * State and queue position move in one transaction (INV-REQ-1156), events leave through the outbox in the command
 * transaction and only the catalogued types (INV-REQ-815), and a tenant reads only its own rows (CS-19).
 */
class SubmissionBoundaryDbTest {
    private val actor = "svc:submission-svc"

    private class Boundary(
        context: ApplicationContext,
    ) {
        val scope: TenantScope = context.getBean(TenantScope::class.java)
        val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        val queue: WorkQueuePort = context.getBean(WorkQueuePort::class.java)
        val outbox: OutboxWriter = context.getBean(OutboxWriter::class.java)
    }

    private fun run(
        dir: Path,
        block: (Boundary) -> Unit,
    ) {
        SubmissionContext.online(dir).run { context -> block(Boundary(context)) }
    }

    private fun Boundary.counts(tenant: UUID): Pair<Int, Int> =
        scope.bind(TenantId(tenant), actor) {
            runner.inTransaction {
                dsl.fetchCount(Submission.SUBMISSION_) to dsl.fetchCount(WorkQueue.WORK_QUEUE)
            }
        }

    private fun Boundary.recordAndQueue(
        tenant: UUID,
        docUuid: UUID,
        failAfter: Boolean,
    ) = scope.bind(TenantId(tenant), actor) {
        runner.inTransaction {
            dsl
                .insertInto(Submission.SUBMISSION_)
                .set(Submission.SUBMISSION_.DOC_UUID, docUuid.toString())
                .set(Submission.SUBMISSION_.VERSION, 1)
                .set(Submission.SUBMISSION_.TENANT_ID, tenant)
                .set(Submission.SUBMISSION_.STATE, "QUEUED")
                .execute()
            queue.enqueue(QueueEntry(docUuid.toString(), Lane.LIVE, "{}"))
            check(!failAfter) { "forced failure after both writes" }
        }
    }

    @Test
    fun `INV-AC-2180 the queue entry and the submission row commit together`(
        @TempDir dir: Path,
    ) = run(dir) { boundary ->
        val tenant = UUID.randomUUID()
        boundary.recordAndQueue(tenant, UUID.randomUUID(), failAfter = false)
        boundary.counts(tenant) shouldBe (1 to 1)
    }

    @Test
    fun `INV-AC-2180 a forced failure leaves the submission row and the queue entry both unchanged`(
        @TempDir dir: Path,
    ) = run(dir) { boundary ->
        val tenant = UUID.randomUUID()
        shouldThrow<IllegalStateException> { boundary.recordAndQueue(tenant, UUID.randomUUID(), failAfter = true) }
        boundary.counts(tenant) shouldBe (0 to 0)
    }

    @Test
    fun `a tenant reads none of the rows of another tenant through the service boundary`(
        @TempDir dir: Path,
    ) = run(dir) { boundary ->
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        boundary.recordAndQueue(a, UUID.randomUUID(), failAfter = false)
        boundary.recordAndQueue(b, UUID.randomUUID(), failAfter = false)
        boundary.counts(a) shouldBe (1 to 1)
        boundary.counts(b) shouldBe (1 to 1)
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
    fun `the emission guard rejects an event type not catalogued for submission and writes nothing`(
        @TempDir dir: Path,
    ) = run(dir) { boundary ->
        val tenant = UUID.randomUUID()
        shouldThrow<EmissionNotPermitted> { boundary.emit(tenant, "com.dynokore.gst.submission.Invented.v1") }
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
        SubmissionEvents.entries.forEach { boundary.emit(tenant, it.eventType) }
        boundary.outboxRows(tenant) shouldBe SubmissionEvents.entries.size
    }
}
