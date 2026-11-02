package com.dynokore.gst.access.domain.breakglass

import java.time.Duration
import java.time.Instant

/**
 * The back-off of the wrong sealing credentials at `signInBreakGlass` for one unlock (INV-AC-9184, INV-Q-9107 A):
 * [failures] consecutive wrong credentials and the instant [nextTryAt] from which the next try is allowed.
 * A wrong credential never locks the path: the wait is at most [MAX_DELAY_SECONDS] seconds.
 */
data class SignInBackoff(
    val failures: Int,
    val nextTryAt: Instant,
) {
    /** True while [now] is before the allowed instant: every sign-in, right or wrong, waits. */
    fun isWaitingAt(now: Instant): Boolean = now.isBefore(nextTryAt)

    /**
     * The remaining wait in whole seconds, rounded up (RFC 9110 section 10.2.3 delta-seconds: rounding up never lets
     * a client retry early); 0 when not waiting.
     */
    fun remainingSecondsAt(now: Instant): Long {
        if (!isWaitingAt(now)) return 0
        val wait = Duration.between(now, nextTryAt)
        return wait.seconds + if (wait.nano > 0) 1 else 0
    }

    /** The state after one credential check at [now]; call it only when the try was allowed. */
    fun after(
        rightCredential: Boolean,
        now: Instant,
    ): SignInBackoff {
        if (rightCredential) return INITIAL
        val count = if (failures == Int.MAX_VALUE) failures else failures + 1
        return SignInBackoff(count, now.plusSeconds(delaySecondsAfter(count)))
    }

    companion object {
        const val FREE_FAILURES = 2
        const val MAX_DELAY_SECONDS = 60L
        private const val CAP_EXPONENT = 6
        private const val FIRST_ALERT_COUNT = 5
        private const val ALERT_EVERY = 10

        /** No failure yet, the first try allowed at once. */
        val INITIAL = SignInBackoff(0, Instant.EPOCH)

        /** The wait after the [failures]-th wrong credential: 0, 0, 1, 2, 4, 8 s and so on, never more than 60 s. */
        fun delaySecondsAfter(failures: Int): Long {
            if (failures <= FREE_FAILURES) return 0
            val exponent = failures - FREE_FAILURES - 1
            // 2^6 = 64 is already above the cap; the guard keeps the shift in range for any count
            return if (exponent >= CAP_EXPONENT) MAX_DELAY_SECONDS else minOf(1L shl exponent, MAX_DELAY_SECONDS)
        }

        /** Whether the failure count [failures] raises the ACC-002 SIGN_IN_FAILED alert: 5, 15, 25 and so on. */
        fun isAlertCount(failures: Int): Boolean =
            failures >= FIRST_ALERT_COUNT && (failures - FIRST_ALERT_COUNT) % ALERT_EVERY == 0
    }
}
