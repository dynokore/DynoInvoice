package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.application.queue.SentResult
import com.dynokore.gst.submission.application.queue.TransportResult
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/** Restart recovery against a real database (INV-REQ-540 behaviour 4, INV-Q-14319 A). */
class SentRecoveryDbTest {
    private val accepted = TransportResult.Accepted("AP-1", Instant.parse("2026-11-01T10:00:05Z"), "ok".toByteArray())

    private fun run(
        dir: Path,
        block: (OutcomeFixture) -> Unit,
    ) {
        SubmissionContext.online(dir).run { context -> block(OutcomeFixture(context)) }
    }

    @Test
    fun `INV-AC-1759 after a restart A is transmitted and B is recovered and re-sent once`(
        @TempDir dir: Path,
    ) = run(dir) { f ->
        val tenant = UUID.randomUUID()
        val docA = UUID.randomUUID().toString()
        val docB = UUID.randomUUID().toString()
        f.queued(tenant, docA)
        f.crashed(tenant, docB, LifecycleState.SENT)
        val transport = FakeTransport(accepted)
        val cycle = f.cycle(transport)

        cycle.runOnce() shouldBe true
        transport.requests.map { it.docUuid } shouldBe listOf(docA)

        val notEligible = f.worker.takeNext() as SentResult.NotEligible
        notEligible.state shouldBe LifecycleState.SENT
        val dispatch = (f.recovery().recover(notEligible) as SentResult.Sent).dispatch
        dispatch.attemptNumber shouldBe 2
        dispatch.docUuid shouldBe docB
        f.row(tenant, docB).state shouldBe "SENT"
        val attempts = f.attempts(tenant, docB)
        attempts.size shouldBe 2
        (attempts[0].correlationId != attempts[1].correlationId) shouldBe true
        f.applier().apply(dispatch, accepted)

        f.itemStates(tenant, docB) shouldBe emptyList()
        f.worker.takeNext() shouldBe null
        f.row(tenant, docB).attemptCount shouldBe 2
    }

    @Test
    fun `a row already SENT_UNCONFIRMED with a CLAIMED item skips the first transaction and is re-sent once`(
        @TempDir dir: Path,
    ) = run(dir) { f ->
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID().toString()
        f.crashed(tenant, doc, LifecycleState.SENT_UNCONFIRMED)
        val transport = FakeTransport(accepted)
        val cycle = f.cycle(transport)

        cycle.runOnce() shouldBe true
        cycle.runOnce() shouldBe false

        transport.requests.size shouldBe 1
        f.row(tenant, doc).attemptCount shouldBe 2
        f.itemStates(tenant, doc) shouldBe emptyList()
    }

    @Test
    fun `a SENT row with an acknowledgement writes nothing and is reported`(
        @TempDir dir: Path,
    ) = run(dir) { f ->
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID().toString()
        f.crashed(tenant, doc, LifecycleState.SENT, ack = "ACK-1")
        val notEligible = f.worker.takeNext() as SentResult.NotEligible

        val result = f.recovery().recover(notEligible)

        (result as SentResult.NotEligible).state shouldBe LifecycleState.SENT
        f.row(tenant, doc).state shouldBe "SENT"
        f.row(tenant, doc).attemptCount shouldBe 1
        f.attempts(tenant, doc).size shouldBe 1
        f.itemStates(tenant, doc) shouldBe listOf("CLAIMED")
    }
}
