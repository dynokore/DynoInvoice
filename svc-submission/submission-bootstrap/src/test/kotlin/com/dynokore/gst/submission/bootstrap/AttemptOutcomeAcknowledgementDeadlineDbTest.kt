package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.application.queue.SentResult
import com.dynokore.gst.submission.application.queue.SubmissionStore
import com.dynokore.gst.submission.application.queue.TransportResult
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** The acknowledgement deadline written with the accepted outcome (INV-T-22769; INV-REQ-975; INV-AC-22762). */
class AttemptOutcomeAcknowledgementDeadlineDbTest {
    private val key = "inv.submission.ack-threshold-minutes"
    private val accepted = TransportResult.Accepted("AP-1", Instant.parse("2026-11-01T10:00:05Z"), "ok".toByteArray())
    private val rejected = TransportResult.Rejected(listOf(rejection()), "no".toByteArray())
    private val timeout = TransportResult.TransientFailure("SUB-001", "timeout".toByteArray())

    private fun rejection() =
        com.dynokore.gst.submission.domain.transitions
            .RejectionReason("GST-001", null, "no")

    private fun run(
        dir: Path,
        block: (OutcomeFixture) -> Unit,
    ) {
        SubmissionContext.online(dir).run { context -> block(OutcomeFixture(context)) }
    }

    private fun deadline(
        f: OutcomeFixture,
        tenant: UUID,
        doc: String,
    ) = f.row(tenant, doc).acknowledgementDeadline

    @Test
    fun `INV-AC-22762 Accepted stores the deadline as the timer start plus the threshold`(
        @TempDir dir: Path,
    ) = run(dir) { f ->
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID().toString()
        f.queued(tenant, doc)
        val dispatch = (f.worker.takeNext() as SentResult.Sent).dispatch

        f.applier().apply(dispatch, accepted)

        val row = f.row(tenant, doc)
        row.state shouldBe "SENT"
        val start = checkNotNull(row.ackTimerStartedAt).toInstant()
        checkNotNull(row.acknowledgementDeadline).toInstant() shouldBe start.plus(Duration.ofMinutes(120))
    }

    @Test
    fun `INV-AC-22762 a failing deadline write leaves response state and item unchanged`(
        @TempDir dir: Path,
    ) = run(dir) { f ->
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID().toString()
        f.queued(tenant, doc)
        val failing =
            object : SubmissionStore by f.store {
                override fun recordAcknowledgementDeadline(
                    tenant: TenantId,
                    docUuid: String,
                    version: Int,
                    deadline: Instant,
                ) = error("forced")
            }
        val dispatch = (f.worker.takeNext() as SentResult.Sent).dispatch

        runCatching { f.applier(failing).apply(dispatch, accepted) }.isFailure shouldBe true

        val row = f.row(tenant, doc)
        row.state shouldBe "SENT"
        row.ackTimerStartedAt shouldBe null
        row.acknowledgementDeadline shouldBe null
        f.attempts(tenant, doc)[0].response shouldBe null
        f.itemStates(tenant, doc) shouldBe listOf("CLAIMED")
    }

    @Test
    fun `INV-AC-22762 Rejected and TransientFailure leave the deadline null`(
        @TempDir dir: Path,
    ) = run(dir) { f ->
        val tenant = UUID.randomUUID()
        val docs = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
        docs.forEach { f.queued(tenant, it) }
        val first = (f.worker.takeNext() as SentResult.Sent).dispatch
        val second = (f.worker.takeNext() as SentResult.Sent).dispatch

        f.applier().apply(first, rejected)
        f.applier().apply(second, timeout)

        deadline(f, tenant, first.docUuid) shouldBe null
        deadline(f, tenant, second.docUuid) shouldBe null
    }

    @Test
    fun `INV-AC-22762 a start with the key missing fails naming the key`(
        @TempDir dir: Path,
    ) {
        // the unresolved placeholder is what application.yaml yields when the environment value is unset
        val runner =
            SubmissionContext
                .offline(dir)
                .withPropertyValues("$key=\${INV_SUBMISSION_ACK_THRESHOLD_MINUTES_UNSET_IN_TEST}")
        shouldFailNaming(runner)
    }

    @Test
    fun `INV-AC-22762 application yaml gives the key no fallback value`() {
        val yaml = Files.readString(Path.of("src/main/resources/application.yaml"))
        yaml.lines().single { it.trim().startsWith("ack-threshold-minutes:") }.trim() shouldBe
            "ack-threshold-minutes: \${INV_SUBMISSION_ACK_THRESHOLD_MINUTES}"
    }

    @Test
    fun `INV-AC-22762 a start with the value 0 fails naming the key`(
        @TempDir dir: Path,
    ) {
        shouldFailNaming(SubmissionContext.offline(dir).withPropertyValues("$key=0"))
    }

    private fun shouldFailNaming(runner: ApplicationContextRunner) {
        runner.run { context ->
            val chain = generateSequence(context.startupFailure) { it.cause }
            (chain.toList().isNotEmpty()) shouldBe true
            chain.joinToString(" | ") { it.message.orEmpty() } shouldContain key
        }
    }
}
