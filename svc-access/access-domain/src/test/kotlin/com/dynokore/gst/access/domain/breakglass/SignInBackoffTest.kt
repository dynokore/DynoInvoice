package com.dynokore.gst.access.domain.breakglass

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

/** INV-AC-9184, slice 1: the schedule, the cap, the rounding and the alert counts as pure functions. */
class SignInBackoffTest {
    private val t0 = Instant.parse("2026-11-02T00:00:00Z")

    @Test
    fun `the delay after failure n for 1 to 9 and 100 is 0 0 1 2 4 8 16 32 60 and 60`() {
        val counts = (1..9).toList() + 100
        assertEquals(listOf(0L, 0, 1, 2, 4, 8, 16, 32, 60, 60), counts.map { SignInBackoff.delaySecondsAfter(it) })
        assertEquals(60L, SignInBackoff.delaySecondsAfter(Int.MAX_VALUE))
    }

    @Test
    fun `a wrong credential counts, sets the allowed instant and a right one resets to zero`() {
        var state = SignInBackoff.INITIAL
        repeat(3) { state = state.after(false, t0) }
        assertEquals(SignInBackoff(3, t0.plusSeconds(1)), state)
        assertEquals(SignInBackoff.INITIAL, state.after(true, t0.plusSeconds(1)))
        assertEquals(SignInBackoff(2, t0), SignInBackoff(1, t0).after(false, t0))
    }

    @Test
    fun `the remaining wait is rounded up to whole seconds and is 0 at the allowed instant`() {
        val state = SignInBackoff(4, t0.plusSeconds(2))
        assertEquals(2L, state.remainingSecondsAt(t0))
        assertEquals(2L, state.remainingSecondsAt(t0.plusMillis(1)))
        assertEquals(1L, state.remainingSecondsAt(t0.plusMillis(1000)))
        assertEquals(1L, state.remainingSecondsAt(t0.plusMillis(1999)))
        assertEquals(0L, state.remainingSecondsAt(t0.plusSeconds(2)))
        assertEquals(true, state.isWaitingAt(t0.plusMillis(1999)))
        assertEquals(false, state.isWaitingAt(t0.plusSeconds(2)))
    }

    @Test
    fun `the alert counts are 5 15 25 and 95 and no others`() {
        val alerting = (1..100).filter { SignInBackoff.isAlertCount(it) }
        assertEquals(listOf(5, 15, 25, 35, 45, 55, 65, 75, 85, 95), alerting)
        listOf(
            1,
            2,
            3,
            4,
            6,
            14,
            16,
            24,
            100,
            0,
            -5,
        ).forEach { assertEquals(false, SignInBackoff.isAlertCount(it), "$it") }
        listOf(5, 15, 25, 95).forEach { assertEquals(true, SignInBackoff.isAlertCount(it), "$it") }
    }
}
