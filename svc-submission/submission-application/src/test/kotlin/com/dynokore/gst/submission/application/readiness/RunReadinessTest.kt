package com.dynokore.gst.submission.application.readiness

import com.dynokore.gst.submission.domain.gate.CheckVerdict
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.readiness.BlockCode
import com.dynokore.gst.submission.domain.readiness.CompletenessState
import com.dynokore.gst.submission.domain.readiness.PeriodVerdict
import com.dynokore.gst.submission.domain.readiness.ReadinessEntry
import com.dynokore.gst.submission.domain.readiness.ReadinessResult
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

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

        val determined = assertIs<ReadinessRunResult.Determined>(run)
        val verdict = assertIs<PeriodVerdict.NotReady>(determined.verdict)
        assertEquals(setOf(BlockCode.PER_001), verdict.codes)
        val result = assertIs<ReadinessResult.Blocked>(determined.result)
        assertEquals(3, result.counts.inScope)
        assertEquals(1, result.counts.acknowledged)
        assertEquals(1, result.counts.queued)
        assertEquals(1, result.counts.blockedOrError)
        assertEquals(2, result.counts.unsubmitted)
        assertEquals(listOf("D2", "D3"), result.residual.map { it.docUuid })
        assertEquals(1, recorder.appended.size)
        val check = recorder.appended.single()
        assertEquals(determined.check, check)
        assertEquals(CheckVerdict.NOT_READY, check.verdict)
        assertEquals("U", check.approver)
        assertEquals(now, check.checkedAt)
        assertEquals(null, check.outcomeValues)
        assertEquals("E", check.entityRef)
        assertEquals("P", check.periodRef)
    }

    @Test
    fun `INV-AC-20474 a clean period gives READY and one stored check`() {
        val recorder = FakeRecorder()
        val run = useCase(recorder, listOf(entry("D1", LifecycleState.ACKNOWLEDGED))).run("E", "P", "U")

        val determined = assertIs<ReadinessRunResult.Determined>(run)
        assertEquals(PeriodVerdict.Ready, determined.verdict)
        assertEquals(listOf(CheckVerdict.READY), recorder.appended.map { it.verdict })
    }

    @Test
    fun `INV-AC-20474 unavailable entry data gives Undetermined and no check`() {
        val recorder = FakeRecorder()
        assertEquals(ReadinessRunResult.Undetermined, useCase(recorder, null).run("E", "P2", "U"))
        assertTrue(recorder.appended.isEmpty())
    }

    @Test
    fun `INV-AC-20474 a failed completeness control adds PER-002 and stores one NOT_READY check`() {
        val recorder = FakeRecorder()
        val run =
            useCase(recorder, listOf(entry("D1", LifecycleState.ACKNOWLEDGED)), CompletenessState.Failed(listOf("C1")))
                .run("E", "P", "U")

        val determined = assertIs<ReadinessRunResult.Determined>(run)
        val verdict = assertIs<PeriodVerdict.NotReady>(determined.verdict)
        assertEquals(setOf(BlockCode.PER_002), verdict.codes)
        assertEquals(listOf("C1"), verdict.failedControls)
        assertEquals(listOf(CheckVerdict.NOT_READY), recorder.appended.map { it.verdict })
    }

    @Test
    fun `INV-AC-20474 a failed control on top of blocked documents carries both codes`() {
        val recorder = FakeRecorder()
        val run = useCase(recorder, mixed, CompletenessState.Failed(listOf("C1"))).run("E", "P", "U")

        val verdict = assertIs<PeriodVerdict.NotReady>(assertIs<ReadinessRunResult.Determined>(run).verdict)
        assertEquals(setOf(BlockCode.PER_001, BlockCode.PER_002), verdict.codes)
    }

    @Test
    fun `INV-AC-20474 unavailable completeness gives Undetermined and no check`() {
        val recorder = FakeRecorder()
        val run = useCase(recorder, mixed, CompletenessState.Unavailable).run("E", "P", "U")
        assertEquals(ReadinessRunResult.Undetermined, run)
        assertTrue(recorder.appended.isEmpty())
    }

    @Test
    fun `INV-AC-20474 blank approver entity or period raises and appends nothing`() {
        val recorder = FakeRecorder()
        val useCase = useCase(recorder, mixed)
        assertThrows<IllegalArgumentException> { useCase.run("E", "P", " ") }
        assertThrows<IllegalArgumentException> { useCase.run("", "P", "U") }
        assertThrows<IllegalArgumentException> { useCase.run("E", "\t", "U") }
        assertTrue(recorder.appended.isEmpty())
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
        assertEquals(null, entriesPort.entries("E", "P"))
        assertEquals(ReadinessInputs(null, CompletenessState.Passed), inputsPort.inputs("E", "P"))
    }
}
