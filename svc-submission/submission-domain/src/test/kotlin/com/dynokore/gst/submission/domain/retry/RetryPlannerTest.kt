package com.dynokore.gst.submission.domain.retry

import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.retry.FailureResult.Anomaly
import com.dynokore.gst.submission.domain.retry.FailureResult.DeadLetter
import com.dynokore.gst.submission.domain.retry.FailureResult.Retry
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.lang.reflect.Modifier
import java.time.Duration
import java.time.Instant

class RetryPlannerTest {
    private val t0 = Instant.parse("2026-04-18T09:00:00Z")
    private val doc = "00000000-0000-0000-0000-000000000001"
    private val raise = listOf(Raise(RetryCode.SUB_001))

    private fun input(
        state: LifecycleState = LifecycleState.QUEUED,
        failures: Int = 0,
        now: Instant = t0,
        boundary: Duration? = Duration.ofHours(4),
        jitter: Double = 0.0,
    ) = FailureInput(
        doc,
        1,
        state,
        failures,
        if (failures >
            0
        ) {
            t0
        } else {
            null
        },
        now,
        boundary,
        BackoffPolicy.DEFAULT,
        jitter,
    )

    private fun plan(
        failures: Int,
        jitter: Double = 0.0,
    ) = RetryPlanner.onTransientFailure(input(failures = failures, jitter = jitter, boundary = Duration.ofDays(3650)))

    private fun delayOf(r: FailureResult) = Duration.between(t0, (r as Retry).nextAttemptAt)

    @Test
    fun `INV-AC-605 QUEUED first failure retries at 15 seconds and raises SUB-001`() {
        RetryPlanner.onTransientFailure(input()) shouldBe
            Retry(doc, 1, LifecycleState.QUEUED, 1, t0.plusSeconds(15), raise)
    }

    @Test
    fun `INV-AC-605 jitter 0_5 adds 7500 milliseconds`() {
        val r = RetryPlanner.onTransientFailure(input(jitter = 0.5))
        (r as Retry).nextAttemptAt shouldBe t0.plusMillis(22_500)
    }

    @Test
    fun `INV-AC-605 RETRY gives the same result as QUEUED apart from from`() {
        val q = RetryPlanner.onTransientFailure(input(LifecycleState.QUEUED)) as Retry
        val r = RetryPlanner.onTransientFailure(input(LifecycleState.RETRY)) as Retry
        r shouldBe q.copy(from = LifecycleState.RETRY)
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 2, 7])
    fun `INV-AC-605 INV-AC-13070 the result keeps docUuid and version and mints no identifier`(version: Int) {
        for (state in listOf(LifecycleState.QUEUED, LifecycleState.RETRY)) {
            val r = RetryPlanner.onTransientFailure(input(state).copy(version = version)) as Retry
            r.docUuid shouldBe doc
            r.version shouldBe version
            r.from shouldBe state
            r.signals shouldBe raise
        }
        val names =
            Retry::class.java.declaredFields
                .filter { !Modifier.isStatic(it.modifiers) }
                .map { it.name }
        names shouldContainExactlyInAnyOrder listOf("docUuid", "version", "from", "attempt", "nextAttemptAt", "signals")
    }

    @Test
    fun `INV-AC-605 delay doubles from 30 seconds and caps the ceiling at 900`() {
        listOf(1 to 30L, 2 to 60L, 3 to 120L, 4 to 240L, 5 to 450L, 999 to 450L).forEach { (failures, seconds) ->
            delayOf(plan(failures)).seconds shouldBe seconds
        }
        delayOf(plan(999, 0.999999)).toMillis() shouldBe 450_000L + 449_999L
    }

    @Test
    fun `INV-AC-605 null boundary dead letters with BOUNDARY_MISSING also at failures 0`() {
        for (failures in listOf(0, 3)) {
            val r = RetryPlanner.onTransientFailure(input(failures = failures, boundary = null))
            r shouldBe DeadLetter(doc, 1, LifecycleState.QUEUED, DeadLetterReason.BOUNDARY_MISSING, raise)
        }
    }

    @Test
    fun `INV-AC-13071 window end is inclusive and one millisecond later is exhausted`() {
        fun at(now: Instant) =
            RetryPlanner.onTransientFailure(
                input(failures = 7, now = now, boundary = Duration.ofHours(1)),
            )
        val ok = at(t0.plusSeconds(3150)) as Retry
        ok.nextAttemptAt shouldBe t0.plusSeconds(3600)
        ok.attempt shouldBe 8
        val late = DeadLetter(doc, 1, LifecycleState.QUEUED, DeadLetterReason.RETRY_WINDOW_EXHAUSTED, raise)
        at(t0.plusSeconds(3150).plusMillis(1)) shouldBe late
        at(t0.plusSeconds(4000)) shouldBe late
    }

    @Test
    fun `INV-AC-13071 first failure window of 10 seconds is exhausted and 15 seconds retries`() {
        RetryPlanner.onTransientFailure(input(boundary = Duration.ofSeconds(10))) shouldBe
            DeadLetter(doc, 1, LifecycleState.QUEUED, DeadLetterReason.RETRY_WINDOW_EXHAUSTED, raise)
        RetryPlanner.onTransientFailure(input(boundary = Duration.ofSeconds(15))) shouldBe
            Retry(doc, 1, LifecycleState.QUEUED, 1, t0.plusSeconds(15), raise)
    }

    @ParameterizedTest
    @EnumSource(LifecycleState::class)
    fun `INV-AC-13071 failure table over all 18 states`(state: LifecycleState) {
        val r = RetryPlanner.onTransientFailure(input(state))
        if (state == LifecycleState.QUEUED || state == LifecycleState.RETRY) {
            (r is Retry) shouldBe true
        } else {
            r shouldBe Anomaly(AnomalyReason.FAILURE_IN_STATE)
        }
    }

    @Test
    fun `INV-AC-611 INV-AC-13072 dead lettered replays to QUEUED and a second replay is unchanged`() {
        RetryPlanner.onReplay(doc, 3, LifecycleState.DEAD_LETTERED) shouldBe
            ReplayResult.Replayed(doc, 3, LifecycleState.DEAD_LETTERED, LifecycleState.QUEUED)
        RetryPlanner.onReplay(doc, 3, LifecycleState.QUEUED) shouldBe ReplayResult.Unchanged
    }

    @ParameterizedTest
    @EnumSource(LifecycleState::class)
    fun `INV-AC-611 INV-AC-13072 replay table over all 18 states`(state: LifecycleState) {
        val r = RetryPlanner.onReplay(doc, 3, state)
        when (state) {
            LifecycleState.DEAD_LETTERED -> {
                r shouldBe ReplayResult.Replayed(doc, 3, LifecycleState.DEAD_LETTERED, LifecycleState.QUEUED)
            }

            LifecycleState.QUEUED -> {
                r shouldBe ReplayResult.Unchanged
            }

            else -> {
                r shouldBe ReplayResult.Anomaly(AnomalyReason.REPLAY_IN_STATE)
            }
        }
    }

    @Test
    fun `INV-AC-611 INV-AC-13072 after a replay a failure with failures 0 gets a fresh window`() {
        val replayed = RetryPlanner.onReplay(doc, 3, LifecycleState.DEAD_LETTERED) as ReplayResult.Replayed
        val now = t0.plusSeconds(5L * 86_400)
        val r =
            RetryPlanner.onTransientFailure(
                input(replayed.to, failures = 0, now = now, boundary = Duration.ofHours(1)),
            )
        r shouldBe Retry(doc, 1, LifecycleState.QUEUED, 1, now.plusSeconds(15), raise)
    }

    @Test
    fun `INV-AC-611 golden table of 36 cells`() {
        var cells = 0
        LifecycleState.entries.forEach { s ->
            val f = RetryPlanner.onTransientFailure(input(s))::class
            val expectedF =
                if (s == LifecycleState.QUEUED ||
                    s == LifecycleState.RETRY
                ) {
                    Retry::class
                } else {
                    Anomaly::class
                }
            f shouldBe expectedF
            val p = RetryPlanner.onReplay(doc, 1, s)::class
            val expectedP =
                when (s) {
                    LifecycleState.DEAD_LETTERED -> ReplayResult.Replayed::class
                    LifecycleState.QUEUED -> ReplayResult.Unchanged::class
                    else -> ReplayResult.Anomaly::class
                }
            p shouldBe expectedP
            cells += 2
        }
        cells shouldBe 36
    }

    @Test
    fun `INV-AC-13070 negative failure count is refused`() {
        assertThrows<IllegalArgumentException> { input(failures = -1) }
    }

    @Test
    fun `INV-AC-13070 failures without firstFailureAt is refused`() {
        assertThrows<IllegalArgumentException> { input(failures = 1).copy(firstFailureAt = null) }
    }

    @Test
    fun `INV-AC-13070 firstFailureAt without failures is refused`() {
        assertThrows<IllegalArgumentException> { input().copy(firstFailureAt = t0) }
    }

    @Test
    fun `INV-AC-13070 firstFailureAt after now is refused`() {
        assertThrows<IllegalArgumentException> { input(failures = 1).copy(firstFailureAt = t0.plusSeconds(1)) }
    }

    @Test
    fun `INV-AC-13070 zero boundary is refused`() {
        assertThrows<IllegalArgumentException> { input(boundary = Duration.ZERO) }
    }

    @Test
    fun `INV-AC-13070 negative boundary is refused`() {
        assertThrows<IllegalArgumentException> { input(boundary = Duration.ofSeconds(-1)) }
    }

    @Test
    fun `INV-AC-13070 zero base delay is refused`() {
        assertThrows<IllegalArgumentException> { BackoffPolicy(Duration.ZERO, 2, Duration.ofMinutes(1)) }
    }

    @Test
    fun `INV-AC-13070 negative base delay is refused`() {
        assertThrows<IllegalArgumentException> { BackoffPolicy(Duration.ofSeconds(-1), 2, Duration.ofMinutes(1)) }
    }

    @Test
    fun `INV-AC-13070 multiplier below 2 is refused`() {
        assertThrows<IllegalArgumentException> { BackoffPolicy(Duration.ofSeconds(1), 1, Duration.ofMinutes(1)) }
    }

    @Test
    fun `INV-AC-13070 cap below base delay is refused`() {
        assertThrows<IllegalArgumentException> { BackoffPolicy(Duration.ofSeconds(10), 2, Duration.ofSeconds(9)) }
    }

    @Test
    fun `INV-AC-13070 blank docUuid is refused`() {
        assertThrows<IllegalArgumentException> { input().copy(docUuid = " ") }
    }

    @Test
    fun `INV-AC-13070 version below 1 is refused`() {
        assertThrows<IllegalArgumentException> { input().copy(version = 0) }
    }

    @Test
    fun `INV-AC-13070 onReplay blank docUuid is refused`() {
        assertThrows<IllegalArgumentException> { RetryPlanner.onReplay(" ", 1, LifecycleState.QUEUED) }
    }

    @Test
    fun `INV-AC-13070 onReplay version below 1 is refused`() {
        assertThrows<IllegalArgumentException> { RetryPlanner.onReplay(doc, 0, LifecycleState.QUEUED) }
    }

    @ParameterizedTest
    @ValueSource(doubles = [-0.1, 1.0, 1.5, Double.NaN])
    fun `INV-AC-13070 jitter outside 0 to below 1 is refused`(jitter: Double) {
        assertThrows<IllegalArgumentException> { input(jitter = jitter) }
    }

    @Test
    fun `INV-AC-13071 LifecycleState equals the union of the schema enums`() {
        val text = File(System.getProperty("gst.contract.dir"), "schemas/lifecycle-states.schema.json").readText()

        fun enumOf(def: String): List<String> {
            val block = Regex("\"$def\"\\s*:\\s*\\{[^\\[]*\"enum\"\\s*:\\s*\\[([^\\]]*)]").find(text)!!.groupValues[1]
            return Regex("\"([A-Z_]+)\"").findAll(block).map { it.groupValues[1] }.toList()
        }
        LifecycleState.entries.map { it.name } shouldContainExactlyInAnyOrder
            enumOf("documentLevelState") + enumOf("versionLevelState")
    }
}
