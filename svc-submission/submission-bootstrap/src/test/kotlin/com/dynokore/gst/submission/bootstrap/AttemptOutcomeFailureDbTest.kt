package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Submission
import com.dynokore.gst.submission.application.queue.Dispatch
import com.dynokore.gst.submission.application.queue.FailureReporting
import com.dynokore.gst.submission.application.queue.SentResult
import com.dynokore.gst.submission.application.queue.TransportResult
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.transitions.RejectionReason
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/** The failure recording and reporting hook of the attempt outcome applier. */
class AttemptOutcomeFailureDbTest {
    private val timeout = TransportResult.TransientFailure("SUB-001", "timeout".toByteArray())

    /** Records each call with the state of the version as the same transaction sees it at that moment. */
    private class Recording(
        private val f: OutcomeFixture,
        private val throwOnReport: Boolean = false,
    ) : FailureReporting {
        val calls = mutableListOf<String>()

        private fun stateNow(dispatch: Dispatch): String =
            checkNotNull(
                f.dsl
                    .select(Submission.SUBMISSION_.STATE)
                    .from(Submission.SUBMISSION_)
                    .where(Submission.SUBMISSION_.DOC_UUID.eq(dispatch.docUuid))
                    .and(Submission.SUBMISSION_.VERSION.eq(dispatch.version))
                    .fetchSingle(Submission.SUBMISSION_.STATE),
            )

        override fun attemptFailed(
            dispatch: Dispatch,
            code: String,
        ) {
            calls += "attempt $code ${dispatch.attemptNumber} seen-state ${stateNow(dispatch)}"
        }

        override fun versionFailed(
            dispatch: Dispatch,
            state: LifecycleState,
            rejectedCode: String?,
        ) {
            calls += "report $state $rejectedCode seen-state ${stateNow(dispatch)}"
            check(!throwOnReport) { "report refused" }
        }
    }

    private fun run(
        dir: Path,
        block: (OutcomeFixture) -> Unit,
    ) {
        SubmissionContext.online(dir).run { context -> block(OutcomeFixture(context)) }
    }

    private fun dispatchOf(
        f: OutcomeFixture,
        tenant: UUID,
        doc: String,
    ): Dispatch {
        f.queued(tenant, doc)
        return (f.worker.takeNext() as SentResult.Sent).dispatch
    }

    @Test
    fun `INV-AC-20655 V1 a rejection records the first reason code then reports REJECTED after the state write`(
        @TempDir dir: Path,
    ) = run(dir) { f ->
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID().toString()
        val rec = Recording(f)
        val dispatch = dispatchOf(f, tenant, doc)
        val reasons = listOf(RejectionReason("R1", null, "a"), RejectionReason("R2", null, "b"))

        f.applier(failures = rec).apply(dispatch, TransportResult.Rejected(reasons, "x".toByteArray()))

        f.row(tenant, doc).state shouldBe "REJECTED"
        rec.calls shouldBe listOf("attempt R1 1 seen-state REJECTED", "report REJECTED R1 seen-state REJECTED")
    }

    @Test
    fun `INV-AC-20655 V2 a retried transient failure records its code and reports nothing`(
        @TempDir dir: Path,
    ) = run(dir) { f ->
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID().toString()
        val rec = Recording(f)
        val dispatch = dispatchOf(f, tenant, doc)

        f.applier(failures = rec).apply(dispatch, timeout)

        f.row(tenant, doc).state shouldBe "RETRY"
        rec.calls.size shouldBe 1
        rec.calls[0].startsWith("attempt SUB-001 1") shouldBe true
    }

    @Test
    fun `INV-AC-20655 V3 a dead-lettering failure records its code then reports DEAD_LETTERED without a rejection code`(
        @TempDir dir: Path,
    ) = run(dir) { f ->
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID().toString()
        val rec = Recording(f)
        f.boundary = null
        val dispatch = dispatchOf(f, tenant, doc)

        f.applier(failures = rec).apply(dispatch, timeout)

        f.row(tenant, doc).state shouldBe "DEAD_LETTERED"
        rec.calls shouldBe
            listOf("attempt SUB-001 1 seen-state DEAD_LETTERED", "report DEAD_LETTERED null seen-state DEAD_LETTERED")
    }

    @Test
    fun `INV-AC-20655 V4 an accepted outcome calls nothing and keeps the accepted rows`(
        @TempDir dir: Path,
    ) = run(dir) { f ->
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID().toString()
        val rec = Recording(f)
        val dispatch = dispatchOf(f, tenant, doc)

        f.applier(failures = rec).apply(
            dispatch,
            TransportResult.Accepted("AP-1", Instant.parse("2026-11-01T10:00:05Z"), "ok".toByteArray()),
        )

        rec.calls shouldBe emptyList()
        f.row(tenant, doc).state shouldBe "SENT"
        (f.row(tenant, doc).submittedAt != null) shouldBe true
        f.attempts(tenant, doc)[0].response.let { String(checkNotNull(it)) } shouldBe "ok"
        f.itemStates(tenant, doc) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20655 V5 a report that throws rolls the whole outcome back`(
        @TempDir dir: Path,
    ) = run(dir) { f ->
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID().toString()
        val dispatch = dispatchOf(f, tenant, doc)
        val reasons = listOf(RejectionReason("R1", null, "a"))

        runCatching {
            f
                .applier(failures = Recording(f, throwOnReport = true))
                .apply(dispatch, TransportResult.Rejected(reasons, "x".toByteArray()))
        }.isFailure shouldBe true

        f.row(tenant, doc).state shouldBe "SENT"
        f.attempts(tenant, doc)[0].response shouldBe null
        f.itemStates(tenant, doc) shouldBe listOf("CLAIMED")
    }

    @Test
    fun `a rejection with an empty reason list stops and writes nothing`(
        @TempDir dir: Path,
    ) = run(dir) { f ->
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID().toString()
        val rec = Recording(f)
        val dispatch = dispatchOf(f, tenant, doc)

        runCatching {
            f.applier(failures = rec).apply(dispatch, TransportResult.Rejected(emptyList(), "x".toByteArray()))
        }.isFailure shouldBe true

        rec.calls shouldBe emptyList()
        f.row(tenant, doc).state shouldBe "SENT"
        f.attempts(tenant, doc)[0].response shouldBe null
    }
}
