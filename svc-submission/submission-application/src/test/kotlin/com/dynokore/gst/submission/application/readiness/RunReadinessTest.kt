package com.dynokore.gst.submission.application.readiness

import com.dynokore.gst.submission.domain.gate.CheckVerdict
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.readiness.BlockCode
import com.dynokore.gst.submission.domain.readiness.CompletenessState
import com.dynokore.gst.submission.domain.readiness.PeriodVerdict
import com.dynokore.gst.submission.domain.readiness.ReadinessEntry
import com.dynokore.gst.submission.domain.readiness.ReadinessResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class RunReadinessTest {
    private val now = Instant.parse("2026-11-12T08:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val day = LocalDate.parse("2026-10-15")

    private class FakeRecorder : ReadinessCheckRecorder {
        val appended = mutableListOf<StoredReadinessCheck>()

        override fun append(
            entityRef: String,
            periodRef: String,
            verdict: CheckVerdict,
            checkedAt: Instant,
            approver: String,
            outcomeValues: String?,
        ): StoredReadinessCheck =
            StoredReadinessCheck(
                appended.size + 1L,
                StoredReadinessCheck.checkIdOf(appended.size + 1L),
                entityRef,
                periodRef,
                verdict,
                checkedAt,
                approver,
                outcomeValues,
            ).also { appended += it }
    }

    private fun entry(
        id: String,
        state: LifecycleState,
    ) = ReadinessEntry(id, listOf(state), day, overridden = false)

    private val mixed =
        listOf(
            entry("D1", LifecycleState.ACKNOWLEDGED),
            entry("D2", LifecycleState.QUEUED),
            entry("D3", LifecycleState.BLOCKED),
        )

    private fun useCase(
        recorder: FakeRecorder,
        entries: List<ReadinessEntry>?,
        completeness: CompletenessState = CompletenessState.Passed,
    ) = RunReadiness(
        { _, _ -> entries },
        { _, _ -> ReadinessInputs(day, completeness) },
        recorder,
        clock,
    )

    @Test
    fun `INV-AC-20474 queued and blocked documents give NOT_READY with PER-001 and one stored check`() {
        val recorder = FakeRecorder()
        val run = useCase(recorder, mixed).run("E", "P", "U")

        val determined = run.shouldBeInstanceOf<ReadinessRunResult.Determined>()
        val verdict = determined.verdict.shouldBeInstanceOf<PeriodVerdict.NotReady>()
        verdict.codes shouldBe setOf(BlockCode.PER_001)
        val result = determined.result.shouldBeInstanceOf<ReadinessResult.Blocked>()
        result.counts.inScope shouldBe 3
        result.counts.acknowledged shouldBe 1
        result.counts.queued shouldBe 1
        result.counts.blockedOrError shouldBe 1
        result.counts.unsubmitted shouldBe 2
        result.residual.map { it.docUuid } shouldBe listOf("D2", "D3")
        recorder.appended.size shouldBe 1
        val check = recorder.appended.single()
        check shouldBe determined.check
        check.verdict shouldBe CheckVerdict.NOT_READY
        check.approver shouldBe "U"
        check.checkedAt shouldBe now
        check.outcomeValues shouldBe null
        check.entityRef shouldBe "E"
        check.periodRef shouldBe "P"
    }

    @Test
    fun `INV-AC-20474 a clean period gives READY and one stored check`() {
        val recorder = FakeRecorder()
        val run = useCase(recorder, listOf(entry("D1", LifecycleState.ACKNOWLEDGED))).run("E", "P", "U")

        val determined = run.shouldBeInstanceOf<ReadinessRunResult.Determined>()
        determined.verdict shouldBe PeriodVerdict.Ready
        recorder.appended.map { it.verdict } shouldBe listOf(CheckVerdict.READY)
    }

    @Test
    fun `INV-AC-20474 unavailable entry data gives Undetermined and no check`() {
        val recorder = FakeRecorder()
        useCase(recorder, null).run("E", "P2", "U") shouldBe ReadinessRunResult.Undetermined
        recorder.appended.isEmpty() shouldBe true
    }

    @Test
    fun `INV-AC-20474 a failed completeness control adds PER-002 and stores one NOT_READY check`() {
        val recorder = FakeRecorder()
        val run =
            useCase(recorder, listOf(entry("D1", LifecycleState.ACKNOWLEDGED)), CompletenessState.Failed(listOf("C1")))
                .run("E", "P", "U")

        val determined = run.shouldBeInstanceOf<ReadinessRunResult.Determined>()
        val verdict = determined.verdict.shouldBeInstanceOf<PeriodVerdict.NotReady>()
        verdict.codes shouldBe setOf(BlockCode.PER_002)
        verdict.failedControls shouldBe listOf("C1")
        recorder.appended.map { it.verdict } shouldBe listOf(CheckVerdict.NOT_READY)
    }

    @Test
    fun `INV-AC-20474 a failed control on top of blocked documents carries both codes`() {
        val recorder = FakeRecorder()
        val run = useCase(recorder, mixed, CompletenessState.Failed(listOf("C1"))).run("E", "P", "U")

        val determined = run.shouldBeInstanceOf<ReadinessRunResult.Determined>()
        val verdict = determined.verdict.shouldBeInstanceOf<PeriodVerdict.NotReady>()
        verdict.codes shouldBe setOf(BlockCode.PER_001, BlockCode.PER_002)
    }

    @Test
    fun `INV-AC-20474 unavailable completeness gives Undetermined and no check`() {
        val recorder = FakeRecorder()
        val run = useCase(recorder, mixed, CompletenessState.Unavailable).run("E", "P", "U")
        run shouldBe ReadinessRunResult.Undetermined
        recorder.appended.isEmpty() shouldBe true
    }

    @Test
    fun `INV-AC-20474 blank approver entity or period raises and appends nothing`() {
        val recorder = FakeRecorder()
        val useCase = useCase(recorder, mixed)
        shouldThrow<IllegalArgumentException> { useCase.run("E", "P", " ") }
        shouldThrow<IllegalArgumentException> { useCase.run("", "P", "U") }
        shouldThrow<IllegalArgumentException> { useCase.run("E", "\t", "U") }
        recorder.appended.isEmpty() shouldBe true
    }

    private object EntriesFixture : ReadinessEntrySource {
        override fun entries(
            entityRef: String,
            periodRef: String,
        ): List<ReadinessEntry>? = null
    }

    private object InputsFixture : ReadinessInputSource {
        override fun inputs(
            entityRef: String,
            periodRef: String,
        ): ReadinessInputs = ReadinessInputs(null, CompletenessState.Passed)
    }

    @Test
    fun `INV-AC-20474 port shapes are callable through the port types`() {
        val entriesPort: ReadinessEntrySource = EntriesFixture
        val inputsPort: ReadinessInputSource = InputsFixture
        entriesPort.entries("E", "P") shouldBe null
        inputsPort.inputs("E", "P") shouldBe ReadinessInputs(null, CompletenessState.Passed)
    }

    @Test
    fun `INV-AC-20556 a run with two HELD residuals records exactly their uuids under heldDocUuids`() {
        val recorder = FakeRecorder()
        val entries =
            listOf(
                entry("D2", LifecycleState.HELD),
                entry("D1", LifecycleState.HELD),
                entry("D3", LifecycleState.QUEUED),
                entry("D4", LifecycleState.ACKNOWLEDGED),
            )
        useCase(recorder, entries).run("E", "P", "U")
        recorder.appended.single().outcomeValues shouldBe """{"heldDocUuids":["D1","D2"]}"""
    }

    @Test
    fun `INV-AC-20556 a run with a QUEUED residual and no HELD one records null`() {
        val recorder = FakeRecorder()
        useCase(recorder, listOf(entry("D3", LifecycleState.QUEUED))).run("E", "P", "U")
        recorder.appended.single().outcomeValues shouldBe null
    }

    @Test
    fun `INV-AC-20556 a uuid with a quote or backslash is escaped into valid json`() {
        val recorder = FakeRecorder()
        useCase(recorder, listOf(entry("a\"b\\c", LifecycleState.HELD))).run("E", "P", "U")
        recorder.appended.single().outcomeValues shouldBe """{"heldDocUuids":["a\"b\\c"]}"""
    }
}
