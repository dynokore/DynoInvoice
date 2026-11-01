package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Submission
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.WorkQueue
import com.dynokore.gst.submission.application.queue.SentResult
import com.dynokore.gst.submission.application.queue.SubmissionQueue
import com.dynokore.gst.submission.application.queue.SubmissionWorker
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.dynokore.platform.outboxandqueue.WorkQueue as WorkQueuePort

/**
 * Documents of one entity under different Doc UUIDs are claimed without waiting for each other
 * (INV-REQ-549 behaviour 1), against a real database (P109).
 */
class DocUuidIndependenceDbTest {
    private val actor = "svc:submission-svc"
    private val payload = "<Invoice/>".toByteArray()
    private val sha = "a".repeat(SHA_LENGTH)

    private class Boundary(
        context: ApplicationContext,
    ) {
        val tenants: TenantContext = context.getBean(TenantContext::class.java)
        val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        val queue: SubmissionQueue = context.getBean(SubmissionQueue::class.java)
        val workQueue: WorkQueuePort = context.getBean(WorkQueuePort::class.java)
        val worker: SubmissionWorker = context.getBean(SubmissionWorker::class.java)
    }

    private fun run(
        dir: Path,
        block: (Boundary) -> Unit,
    ) {
        SubmissionContext.online(dir).run { context -> block(Boundary(context).also { it.drain() }) }
    }

    private fun <T> Boundary.asTenant(
        tenant: UUID,
        block: () -> T,
    ): T = tenants.bind(TenantId(tenant), actor) { runner.inTransaction(block) }

    private fun Boundary.seed(
        tenant: UUID,
        docUuid: String,
        state: LifecycleState,
        version: Int = 1,
    ) = asTenant(tenant) {
        dsl
            .insertInto(Submission.SUBMISSION_)
            .set(Submission.SUBMISSION_.TENANT_ID, tenant)
            .set(Submission.SUBMISSION_.DOC_UUID, docUuid)
            .set(Submission.SUBMISSION_.VERSION, version)
            .set(Submission.SUBMISSION_.STATE, state.name)
            .set(Submission.SUBMISSION_.PAYLOAD_BYTES, payload)
            .set(Submission.SUBMISSION_.PAYLOAD_SHA256, sha)
            .set(Submission.SUBMISSION_.ENTITY_REF, "E-001")
            .execute()
    }

    /** Seeds a `READY` version and enqueues it through the use case. */
    private fun Boundary.queued(
        tenant: UUID,
        docUuid: String,
        version: Int = 1,
    ) {
        seed(tenant, docUuid, LifecycleState.READY, version)
        asTenant(tenant) { queue.enqueue(docUuid, version) }
    }

    private fun Boundary.stateOf(
        tenant: UUID,
        docUuid: String,
        version: Int = 1,
    ): String =
        asTenant(tenant) {
            checkNotNull(
                dsl
                    .select(Submission.SUBMISSION_.STATE)
                    .from(Submission.SUBMISSION_)
                    .where(Submission.SUBMISSION_.DOC_UUID.eq(docUuid))
                    .and(Submission.SUBMISSION_.VERSION.eq(version))
                    .fetchOne(Submission.SUBMISSION_.STATE),
            )
        }

    private fun Boundary.attemptCount(
        tenant: UUID,
        docUuid: String,
    ): Int =
        asTenant(tenant) {
            checkNotNull(
                dsl
                    .select(Submission.SUBMISSION_.ATTEMPT_COUNT)
                    .from(Submission.SUBMISSION_)
                    .where(Submission.SUBMISSION_.DOC_UUID.eq(docUuid))
                    .fetchOne(Submission.SUBMISSION_.ATTEMPT_COUNT),
            )
        }

    private fun Boundary.itemStates(
        tenant: UUID,
        docUuid: String,
    ): List<String> =
        asTenant(tenant) {
            dsl
                .select(WorkQueue.WORK_QUEUE.STATE)
                .from(WorkQueue.WORK_QUEUE)
                .where(WorkQueue.WORK_QUEUE.PARTITION_KEY.eq(docUuid))
                .fetch(WorkQueue.WORK_QUEUE.STATE)
                .map { checkNotNull(it) }
        }

    /** Drops the items earlier tests left: `claimNext` is cross-tenant, so a leftover would be claimed here. */
    private fun Boundary.drain() {
        while (true) {
            val item = workQueue.claimNext() ?: break
            tenants.bind(item.tenant, actor) { runner.inTransaction { workQueue.complete(item) } }
        }
    }

    private fun Boundary.sentOrFail(result: SentResult?): SentResult.Sent =
        (result as? SentResult.Sent) ?: error("expected a Sent result, got $result")

    /** A tenant with two READY versions of one entity under fresh Doc UUIDs, A enqueued before B. */
    private fun Boundary.twoDocs(): Triple<UUID, String, String> {
        val tenant = UUID.randomUUID()
        val a = UUID.randomUUID().toString()
        val b = UUID.randomUUID().toString()
        queued(tenant, a)
        queued(tenant, b)
        return Triple(tenant, a, b)
    }

    @Test
    fun `INV-AC-14143 B is claimed and sent while A is SENT and unacknowledged`(
        @TempDir dir: Path,
    ) = run(dir) { boundary ->
        val (tenant, a, b) = boundary.twoDocs()

        boundary.sentOrFail(boundary.worker.takeNext()).dispatch.docUuid shouldBe a
        boundary.sentOrFail(boundary.worker.takeNext()).dispatch.docUuid shouldBe b

        boundary.stateOf(tenant, a) shouldBe "SENT"
        boundary.attemptCount(tenant, a) shouldBe 1
        boundary.stateOf(tenant, b) shouldBe "SENT"
        boundary.itemStates(tenant, a) shouldBe listOf("CLAIMED")
        boundary.itemStates(tenant, b) shouldBe listOf("CLAIMED")
        boundary.worker.takeNext() shouldBe null
    }

    @Test
    fun `INV-AC-14143 B is claimed and sent while A is only claimed`(
        @TempDir dir: Path,
    ) = run(dir) { boundary ->
        val (tenant, a, b) = boundary.twoDocs()

        checkNotNull(boundary.workQueue.claimNext())
        boundary.itemStates(tenant, a) shouldBe listOf("CLAIMED")
        boundary.sentOrFail(boundary.worker.takeNext()).dispatch.docUuid shouldBe b

        boundary.stateOf(tenant, a) shouldBe "QUEUED"
        boundary.itemStates(tenant, a) shouldBe listOf("CLAIMED")
        boundary.stateOf(tenant, b) shouldBe "SENT"
    }

    @Test
    fun `INV-AC-14143 two workers at once receive A and B one each`(
        @TempDir dir: Path,
    ) = run(dir) { boundary ->
        val (_, a, b) = boundary.twoDocs()
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures =
                List(2) {
                    pool.submit<SentResult?> {
                        barrier.await(WAIT_SECONDS, TimeUnit.SECONDS)
                        boundary.worker.takeNext()
                    }
                }
            val taken =
                futures.map {
                    val d = boundary.sentOrFail(it.get(WAIT_SECONDS, TimeUnit.SECONDS)).dispatch
                    d.docUuid to d.version
                }

            taken.toSet().size shouldBe 2
            taken.map { it.first }.toSet() shouldBe setOf(a, b)
        } finally {
            pool.shutdownNow()
        }
    }

    private companion object {
        const val WAIT_SECONDS = 120L
        const val SHA_LENGTH = 64
    }
}
