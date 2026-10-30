package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Outbox
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Submission
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.SubmissionAttempt
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.WorkQueue
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.records.OutboxRecord
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.records.SubmissionAttemptRecord
import com.dynokore.gst.submission.application.queue.SentResult
import com.dynokore.gst.submission.application.queue.SentTransaction
import com.dynokore.gst.submission.application.queue.SubmissionQueue
import com.dynokore.gst.submission.application.queue.SubmissionWorker
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.outboxandqueue.Lane
import com.dynokore.platform.outboxandqueue.QueueEntry
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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.dynokore.platform.outboxandqueue.WorkQueue as WorkQueuePort

/**
 * The claim side of the queue against a real database (P109): `SubmissionWorker.takeNext` and
 * `SentTransaction.perform` (INV-REQ-540 behaviour 2, INV-REQ-1156 behaviour 2).
 */
class SubmissionWorkerDbTest {
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
        val sent: SentTransaction = context.getBean(SentTransaction::class.java)
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

    private fun Boundary.attempts(
        tenant: UUID,
        docUuid: String,
    ): List<SubmissionAttemptRecord> =
        asTenant(tenant) {
            dsl
                .selectFrom(SubmissionAttempt.SUBMISSION_ATTEMPT)
                .where(SubmissionAttempt.SUBMISSION_ATTEMPT.DOC_UUID.eq(docUuid))
                .orderBy(SubmissionAttempt.SUBMISSION_ATTEMPT.ATTEMPT_NUMBER)
                .fetch()
                .toList()
        }

    private fun Boundary.outboxRows(
        tenant: UUID,
        docUuid: String,
    ): List<OutboxRecord> =
        asTenant(tenant) {
            dsl
                .selectFrom(Outbox.OUTBOX)
                .where(Outbox.OUTBOX.DOC_UUID.eq(UUID.fromString(docUuid)))
                .fetch()
                .toList()
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

    @Test
    fun `INV-AC-13068 takeNext moves a queued version to SENT in one transaction and the item stays CLAIMED`(
        @TempDir dir: Path,
    ) = run(dir) { boundary ->
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID().toString()
        boundary.queued(tenant, doc)

        val dispatch = boundary.sentOrFail(boundary.worker.takeNext()).dispatch

        dispatch.docUuid shouldBe doc
        dispatch.version shouldBe 1
        dispatch.attemptNumber shouldBe 1
        dispatch.requestBytes?.toList() shouldBe payload.toList()
        boundary.stateOf(tenant, doc) shouldBe "SENT"
        boundary.attemptCount(tenant, doc) shouldBe 1
        val attempts = boundary.attempts(tenant, doc)
        attempts.size shouldBe 1
        checkNotNull(attempts[0].request).toList() shouldBe payload.toList()
        attempts[0].correlationId.toString() shouldBe dispatch.correlationId.toString()
        (attempts[0].requestedAt != null) shouldBe true
        val events = boundary.outboxRows(tenant, doc)
        events.size shouldBe 1
        events[0].type shouldBe "com.dynokore.gst.submission.SubmissionDespatched.v1"
        events[0].entityRef shouldBe "E-001"
        checkNotNull(events[0].data).data().replace(" ", "") shouldBe
            """{"docUuid":"$doc","version":1,"attemptNumber":1,"payloadSha256":"$sha"}"""
        boundary.itemStates(tenant, doc) shouldBe listOf("CLAIMED")
    }

    @Test
    fun `takeNext gives no work on an empty queue`(
        @TempDir dir: Path,
    ) = run(dir) { boundary ->
        boundary.worker.takeNext() shouldBe null
    }

    @Test
    fun `INV-AC-13068 perform outside the eligible set writes nothing and inside it moves the row to SENT`(
        @TempDir dir: Path,
    ) = run(dir) { boundary ->
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID().toString()
        boundary.seed(tenant, doc, LifecycleState.SENT_UNCONFIRMED)
        boundary.asTenant(tenant) {
            boundary.workQueue.enqueue(QueueEntry(doc, Lane.LIVE, """{"docUuid":"$doc","version":1}"""))
        }
        val item = checkNotNull(boundary.workQueue.claimNext())

        val refused = boundary.sent.perform(item, setOf(LifecycleState.QUEUED, LifecycleState.RETRY))

        refused shouldBe SentResult.NotEligible(LifecycleState.SENT_UNCONFIRMED, item)
        boundary.stateOf(tenant, doc) shouldBe "SENT_UNCONFIRMED"
        boundary.attemptCount(tenant, doc) shouldBe 0
        boundary.attempts(tenant, doc).size shouldBe 0
        boundary.outboxRows(tenant, doc).size shouldBe 0
        boundary.itemStates(tenant, doc) shouldBe listOf("CLAIMED")

        boundary.sentOrFail(boundary.sent.perform(item, setOf(LifecycleState.SENT_UNCONFIRMED)))

        boundary.stateOf(tenant, doc) shouldBe "SENT"
        boundary.attempts(tenant, doc).size shouldBe 1
        boundary.outboxRows(tenant, doc).size shouldBe 1
    }

    @Test
    fun `INV-AC-13067 two workers draining 20 distinct documents never return one version twice`(
        @TempDir dir: Path,
    ) = run(dir) { boundary ->
        val tenant = UUID.randomUUID()
        val docs = List(DOCS) { UUID.randomUUID().toString() }
        docs.forEach { boundary.queued(tenant, it) }
        val taken = ConcurrentLinkedQueue<Pair<String, Int>>()
        val pool = Executors.newFixedThreadPool(2)
        val futures =
            List(2) {
                pool.submit {
                    while (true) {
                        val result = boundary.worker.takeNext() ?: break
                        val d = boundary.sentOrFail(result).dispatch
                        taken.add(d.docUuid to d.version)
                    }
                }
            }
        futures.forEach { it.get(WAIT_SECONDS, TimeUnit.SECONDS) }
        pool.shutdown()

        taken.size shouldBe DOCS
        taken.toSet() shouldBe docs.map { it to 1 }.toSet()
    }

    @Test
    fun `two queued versions of one document are not both returned while the first is unfinished`(
        @TempDir dir: Path,
    ) = run(dir) { boundary ->
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID().toString()
        boundary.queued(tenant, doc, version = 1)
        boundary.queued(tenant, doc, version = 2)

        boundary.sentOrFail(boundary.worker.takeNext()).dispatch.version shouldBe 1
        boundary.worker.takeNext() shouldBe null
        boundary.stateOf(tenant, doc, version = 2) shouldBe "QUEUED"
    }

    @Test
    fun `a claim without step 3 leaves the state unchanged and the item CLAIMED`(
        @TempDir dir: Path,
    ) = run(dir) { boundary ->
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID().toString()
        boundary.queued(tenant, doc)

        checkNotNull(boundary.workQueue.claimNext())

        boundary.stateOf(tenant, doc) shouldBe "QUEUED"
        boundary.attemptCount(tenant, doc) shouldBe 0
        boundary.itemStates(tenant, doc) shouldBe listOf("CLAIMED")
    }

    private companion object {
        const val DOCS = 20
        const val WAIT_SECONDS = 120L
        const val SHA_LENGTH = 64
    }
}
