package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.application.queue.RetrySchedule
import com.dynokore.gst.submission.application.queue.SentResult
import com.dynokore.gst.submission.application.queue.SubmissionStore
import com.dynokore.gst.submission.application.queue.TransportResult
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.retry.BackoffPolicy
import com.dynokore.gst.submission.domain.retry.FailureInput
import com.dynokore.gst.submission.domain.retry.FailureResult
import com.dynokore.gst.submission.domain.retry.RetryPlanner
import com.dynokore.gst.submission.domain.transitions.RejectionReason
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** The outcome of a transmission attempt against a real database (INV-REQ-540 behaviour 3, INV-Q-7266 A step (5)). */
class AttemptOutcomeDbTest {
    private val timeout = TransportResult.TransientFailure("SUB-001", "timeout".toByteArray())
    private val accepted = TransportResult.Accepted("AP-1", Instant.parse("2026-11-01T10:00:05Z"), "ok".toByteArray())

    private fun run(
        dir: Path,
        block: (OutcomeFixture) -> Unit,
    ) {
        SubmissionContext.online(dir).run { context -> block(OutcomeFixture(context)) }
    }

    private fun input(clock: Instant) =
        FailureInput("d", 1, LifecycleState.QUEUED, 0, null, clock, Duration.ofHours(4), BackoffPolicy.DEFAULT, 0.0)

    private fun firstDelayTarget(clock: Instant): Instant =
        (
            RetryPlanner.onTransientFailure(
                input(clock),
            ) as FailureResult.Retry
        ).nextAttemptAt

    @Test
    fun `INV-AC-1760 a version transmitted twice has two complete attempt records`(
        @TempDir dir: Path,
    ) = run(dir) { f ->
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID().toString()
        val transport = FakeTransport(timeout, accepted)
        f.queued(tenant, doc)
        val cycle = f.cycle(transport)

        cycle.runOnce() shouldBe true
        f.clock.now = firstDelayTarget(f.clock.now)
        f.dueRunner { listOf(TenantId(tenant)) }.runOnce(f.clock.now)
        cycle.runOnce() shouldBe true

        val attempts = f.attempts(tenant, doc)
        attempts.size shouldBe 2
        attempts.forEach {
            listOf(it.request, it.response, it.requestedAt, it.respondedAt, it.correlationId).all { v ->
                v != null
            } shouldBe
                true
        }
        attempts.map { it.correlationId }.toSet().size shouldBe 2
        String(checkNotNull(attempts[0].response)) shouldBe "timeout"
        String(checkNotNull(attempts[1].response)) shouldBe "ok"
    }

    @Test
    fun `INV-AC-605 the same Doc UUID and version is retried with no new row`(
        @TempDir dir: Path,
    ) = run(dir) { f ->
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID().toString()
        val transport = FakeTransport(timeout, accepted)
        f.queued(tenant, doc)
        val cycle = f.cycle(transport)
        val start = f.clock.now
        val due = firstDelayTarget(start)

        cycle.runOnce()

        val retry = f.row(tenant, doc)
        retry.state shouldBe "RETRY"
        retry.retryFailures shouldBe 1
        retry.retryFirstFailureAt?.toInstant() shouldBe start
        retry.nextAttemptAt?.toInstant() shouldBe due
        f.itemStates(tenant, doc) shouldBe emptyList()

        f.clock.now = due
        f.dueRunner { listOf(TenantId(tenant)) }.runOnce(due)
        f.itemStates(tenant, doc) shouldBe listOf("PENDING")
        cycle.runOnce()

        transport.requests.map { it.docUuid to it.version } shouldBe listOf(doc to 1, doc to 1)
        (transport.requests[0].idempotencyKey != transport.requests[1].idempotencyKey) shouldBe true
        val done = f.row(tenant, doc)
        done.attemptCount shouldBe 2
        done.retryFailures shouldBe 0
        done.retryFirstFailureAt shouldBe null
        done.ackTimerStartedAt?.toInstant() shouldBe due
        f.rows(tenant, doc) shouldBe 1
        f.itemStates(tenant, doc) shouldBe emptyList()
    }

    @Test
    fun `a boundary shorter than the first delay dead-letters at once with no item and no next attempt`(
        @TempDir dir: Path,
    ) = run(dir) { f ->
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID().toString()
        f.boundary = Duration.ofSeconds(1)
        f.queued(tenant, doc)

        f.cycle(FakeTransport(timeout)).runOnce()

        val row = f.row(tenant, doc)
        row.state shouldBe "DEAD_LETTERED"
        row.nextAttemptAt shouldBe null
        row.retryFailures shouldBe 0
        f.itemStates(tenant, doc) shouldBe emptyList()
    }

    @Test
    fun `a null boundary dead-letters the version`(
        @TempDir dir: Path,
    ) = run(dir) { f ->
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID().toString()
        f.boundary = null
        f.queued(tenant, doc)

        f.cycle(FakeTransport(timeout)).runOnce()

        f.row(tenant, doc).state shouldBe "DEAD_LETTERED"
        f.itemStates(tenant, doc) shouldBe emptyList()
    }

    @Test
    fun `a Rejected result gives REJECTED and keeps the reasons in the response`(
        @TempDir dir: Path,
    ) = run(dir) { f ->
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID().toString()
        f.queued(tenant, doc)
        val body = """{"reasons":["R1"]}""".toByteArray()

        f
            .cycle(
                FakeTransport(TransportResult.Rejected(listOf(RejectionReason("SUB-003", null, "bad")), body)),
            ).runOnce()

        f.row(tenant, doc).state shouldBe "REJECTED"
        String(checkNotNull(f.attempts(tenant, doc)[0].response)) shouldBe String(body)
        f.itemStates(tenant, doc) shouldBe emptyList()
    }

    @Test
    fun `DueRetryRunner binds each tenant listed and a tenant with no due row enqueues nothing`(
        @TempDir dir: Path,
    ) = run(dir) { f ->
        val withRow = UUID.randomUUID()
        val without = UUID.randomUUID()
        val doc = UUID.randomUUID().toString()
        f.seed(withRow, doc, LifecycleState.RETRY)
        val now = f.clock.now
        f.asTenant(withRow) { f.store.scheduleRetry(TenantId(withRow), doc, 1, RetrySchedule(now, 1, now)) }

        f.dueRunner { listOf(TenantId(without), TenantId(withRow)) }.runOnce(now)

        f.itemStates(withRow, doc) shouldBe listOf("PENDING")
        f.dueRunner(f.realDueTenants).runOnce(now)
        f.itemStates(withRow, doc) shouldBe listOf("PENDING")
    }

    @Test
    fun `a forced exception inside the outcome transaction leaves everything unchanged`(
        @TempDir dir: Path,
    ) = run(dir) { f ->
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID().toString()
        f.queued(tenant, doc)
        val failing =
            object : SubmissionStore by f.store {
                override fun scheduleRetry(
                    tenant: TenantId,
                    docUuid: String,
                    version: Int,
                    retry: RetrySchedule,
                ) = error("forced")
            }
        val dispatch = (f.worker.takeNext() as SentResult.Sent).dispatch

        runCatching { f.applier(failing).apply(dispatch, timeout) }.isFailure shouldBe true

        val row = f.row(tenant, doc)
        row.state shouldBe "SENT"
        row.retryFailures shouldBe 0
        row.nextAttemptAt shouldBe null
        f.attempts(tenant, doc)[0].response shouldBe null
        f.itemStates(tenant, doc) shouldBe listOf("CLAIMED")
    }

    @Test
    fun `INV-REQ-571 a Rejected outcome releases the waiting READY version after the commit`(
        @TempDir dir: Path,
    ) = run(dir) { f ->
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID().toString()
        f.queued(tenant, doc)
        f.waiting(tenant, doc)

        val rejected = TransportResult.Rejected(listOf(RejectionReason("SUB-003", null, "bad")), "x".toByteArray())

        f.cycle(FakeTransport(rejected)).runOnce()

        f.stateOf(tenant, doc, 1) shouldBe "REJECTED"
        f.stateOf(tenant, doc, 2) shouldBe "QUEUED"
        f.itemStates(tenant, doc) shouldBe listOf("PENDING")
    }

    @Test
    fun `INV-REQ-571 a dead-lettered outcome releases the waiting READY version`(
        @TempDir dir: Path,
    ) = run(dir) { f ->
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID().toString()
        f.boundary = null
        f.queued(tenant, doc)
        f.waiting(tenant, doc)

        f.cycle(FakeTransport(timeout)).runOnce()

        f.stateOf(tenant, doc, 1) shouldBe "DEAD_LETTERED"
        f.stateOf(tenant, doc, 2) shouldBe "QUEUED"
        f.itemStates(tenant, doc) shouldBe listOf("PENDING")
    }

    @Test
    fun `INV-REQ-571 a RETRY outcome keeps the version in flight and releases nothing`(
        @TempDir dir: Path,
    ) = run(dir) { f ->
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID().toString()
        f.queued(tenant, doc)
        f.waiting(tenant, doc)

        f.cycle(FakeTransport(timeout)).runOnce()

        f.stateOf(tenant, doc, 1) shouldBe "RETRY"
        f.stateOf(tenant, doc, 2) shouldBe "READY"
        f.itemStates(tenant, doc) shouldBe emptyList()
    }

    @Test
    fun `a releaseNext failure after the commit never rolls back the outcome`(
        @TempDir dir: Path,
    ) = run(dir) { f ->
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID().toString()
        f.queued(tenant, doc)
        f.waiting(tenant, doc)
        val dispatch = (f.worker.takeNext() as SentResult.Sent).dispatch
        val rejected = TransportResult.Rejected(listOf(RejectionReason("SUB-003", null, "bad")), "x".toByteArray())

        f.applier(releasing = f.failingQueue()).apply(dispatch, rejected)

        f.stateOf(tenant, doc, 1) shouldBe "REJECTED"
        f.attempts(tenant, doc)[0].response?.let { String(it) } shouldBe "x"
        f.stateOf(tenant, doc, 2) shouldBe "READY"
        f.itemStates(tenant, doc) shouldBe emptyList()
    }
}
