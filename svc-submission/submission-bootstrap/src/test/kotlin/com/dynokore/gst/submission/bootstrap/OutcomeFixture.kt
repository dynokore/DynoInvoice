package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Submission
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.SubmissionAttempt
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.WorkQueue
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.records.SubmissionAttemptRecord
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.records.SubmissionRecord
import com.dynokore.gst.submission.application.queue.AttemptOutcomeApplier
import com.dynokore.gst.submission.application.queue.DespatchCycle
import com.dynokore.gst.submission.application.queue.DueRetryRunner
import com.dynokore.gst.submission.application.queue.DueTenantSource
import com.dynokore.gst.submission.application.queue.JitterSource
import com.dynokore.gst.submission.application.queue.NewAttempt
import com.dynokore.gst.submission.application.queue.RetryBoundarySource
import com.dynokore.gst.submission.application.queue.RetryDueScan
import com.dynokore.gst.submission.application.queue.SentRecovery
import com.dynokore.gst.submission.application.queue.SentTransaction
import com.dynokore.gst.submission.application.queue.SubmissionQueue
import com.dynokore.gst.submission.application.queue.SubmissionStore
import com.dynokore.gst.submission.application.queue.SubmissionWorker
import com.dynokore.gst.submission.application.queue.TenantTransactions
import com.dynokore.gst.submission.application.queue.TransportClient
import com.dynokore.gst.submission.application.queue.TransportRequest
import com.dynokore.gst.submission.application.queue.TransportResult
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.outboxandqueue.Lane
import com.dynokore.platform.outboxandqueue.QueueEntry
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.context.ApplicationContext
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import com.dynokore.platform.outboxandqueue.WorkQueue as WorkQueuePort

/** A clock the test moves. */
class MovableClock(
    var now: Instant,
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = now
}

/** A transport that answers from a list and records every request it receives. */
class FakeTransport(
    private vararg val answers: TransportResult,
) : TransportClient {
    val requests = mutableListOf<TransportRequest>()

    override fun submit(request: TransportRequest): TransportResult {
        val answer = answers[requests.size.coerceAtMost(answers.size - 1)]
        requests += request
        return answer
    }
}

/** The real beans of the service composed with a fixed clock and fakes at the ports (P109). */
class OutcomeFixture(
    context: ApplicationContext,
) {
    val actor = "svc:submission-svc"
    val payload = "<Invoice/>".toByteArray()
    val clock = MovableClock(Instant.parse("2026-11-01T10:00:00Z"))
    var boundary: Duration? = Duration.ofHours(4)
    val tenants: TenantContext = context.getBean(TenantContext::class.java)
    val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
    val dsl: DSLContext = context.getBean(DSLContext::class.java)
    val queue: SubmissionQueue = context.getBean(SubmissionQueue::class.java)
    val workQueue: WorkQueuePort = context.getBean(WorkQueuePort::class.java)
    val store: SubmissionStore = context.getBean(SubmissionStore::class.java)
    val worker: SubmissionWorker = context.getBean(SubmissionWorker::class.java)
    val sent: SentTransaction = context.getBean(SentTransaction::class.java)
    val scan: RetryDueScan = context.getBean(RetryDueScan::class.java)
    val scope: TenantTransactions = context.getBean(TenantTransactions::class.java)
    val realDueTenants: DueTenantSource = context.getBean(DueTenantSource::class.java)
    private val boundaries = RetryBoundarySource { _, _ -> boundary }
    private val jitter = JitterSource { 0.0 }

    init {
        while (true) {
            val item = workQueue.claimNext() ?: break
            tenants.bind(item.tenant, actor) { runner.inTransaction { workQueue.complete(item) } }
        }
    }

    fun applier(over: SubmissionStore = store) =
        AttemptOutcomeApplier(clock, boundaries, jitter, scope, over, workQueue, queue)

    fun recovery() = SentRecovery(clock, scope, store, workQueue, sent)

    fun cycle(transport: TransportClient) = DespatchCycle(worker, recovery(), transport, applier())

    fun dueRunner(source: DueTenantSource) = DueRetryRunner(source, scope, scan)

    fun <T> asTenant(
        tenant: UUID,
        block: () -> T,
    ): T = tenants.bind(TenantId(tenant), actor) { runner.inTransaction(block) }

    /** Seeds a version; [ack] fills `acknowledgement_id`, [attempts] the attempt count. */
    fun seed(
        tenant: UUID,
        doc: String,
        state: LifecycleState,
        attempts: Int = 0,
        ack: String? = null,
    ) = asTenant(tenant) {
        dsl
            .insertInto(Submission.SUBMISSION_)
            .set(Submission.SUBMISSION_.TENANT_ID, tenant)
            .set(Submission.SUBMISSION_.DOC_UUID, doc)
            .set(Submission.SUBMISSION_.VERSION, 1)
            .set(Submission.SUBMISSION_.STATE, state.name)
            .set(Submission.SUBMISSION_.PAYLOAD_BYTES, payload)
            .set(Submission.SUBMISSION_.PAYLOAD_SHA256, "a".repeat(SHA_LENGTH))
            .set(Submission.SUBMISSION_.ENTITY_REF, "E-001")
            .set(Submission.SUBMISSION_.ATTEMPT_COUNT, attempts)
            .set(Submission.SUBMISSION_.ACKNOWLEDGEMENT_ID, ack)
            .execute()
    }

    fun queued(
        tenant: UUID,
        doc: String,
    ) {
        seed(tenant, doc, LifecycleState.READY)
        asTenant(tenant) { queue.enqueue(doc, 1) }
    }

    /** A version found `SENT`-like after a crash: row, first attempt row and a CLAIMED item claimed ten minutes ago. */
    fun crashed(
        tenant: UUID,
        doc: String,
        state: LifecycleState,
        ack: String? = null,
    ) {
        seed(tenant, doc, state, attempts = 1, ack = ack)
        asTenant(tenant) {
            store.insertAttempt(
                TenantId(tenant),
                NewAttempt(doc, 1, 1, payload, UUID.randomUUID(), clock.instant()),
            )
            workQueue.enqueue(QueueEntry(doc, Lane.LIVE, """{"docUuid":"$doc","version":1}"""))
        }
        val claimedAt = Instant.now().minus(Duration.ofMinutes(CLAIM_AGE)).atOffset(ZoneOffset.UTC)
        asTenant(tenant) {
            dsl
                .update(WorkQueue.WORK_QUEUE)
                .set(WorkQueue.WORK_QUEUE.STATE, "CLAIMED")
                .set(WorkQueue.WORK_QUEUE.CLAIMED_AT, claimedAt)
                .where(WorkQueue.WORK_QUEUE.PARTITION_KEY.eq(doc))
                .execute()
        }
    }

    fun row(
        tenant: UUID,
        doc: String,
    ): SubmissionRecord =
        asTenant(tenant) {
            dsl.selectFrom(Submission.SUBMISSION_).where(Submission.SUBMISSION_.DOC_UUID.eq(doc)).fetchSingle()
        }

    fun rows(
        tenant: UUID,
        doc: String,
    ): Int = asTenant(tenant) { dsl.fetchCount(Submission.SUBMISSION_, Submission.SUBMISSION_.DOC_UUID.eq(doc)) }

    fun attempts(
        tenant: UUID,
        doc: String,
    ): List<SubmissionAttemptRecord> =
        asTenant(tenant) {
            dsl
                .selectFrom(SubmissionAttempt.SUBMISSION_ATTEMPT)
                .where(SubmissionAttempt.SUBMISSION_ATTEMPT.DOC_UUID.eq(doc))
                .orderBy(SubmissionAttempt.SUBMISSION_ATTEMPT.ATTEMPT_NUMBER)
                .fetch()
                .toList()
        }

    fun itemStates(
        tenant: UUID,
        doc: String,
    ): List<String> =
        asTenant(tenant) {
            dsl
                .select(WorkQueue.WORK_QUEUE.STATE)
                .from(WorkQueue.WORK_QUEUE)
                .where(WorkQueue.WORK_QUEUE.PARTITION_KEY.eq(doc))
                .fetch(WorkQueue.WORK_QUEUE.STATE)
                .map { checkNotNull(it) }
        }

    companion object {
        const val SHA_LENGTH = 64
        const val CLAIM_AGE = 10L
    }
}
