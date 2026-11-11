package com.dynokore.gst.rules.bootstrap

import com.dynokore.gst.rules.application.LastCompletePeriodPort
import com.dynokore.gst.rules.application.PeriodCalendarUnavailableException
import com.dynokore.platform.tenancykernel.TenantId
import java.util.concurrent.atomic.AtomicInteger

/**
 * Canned period calendar of the test composition (INV-T-4974; INV-REQ-1303): answers [answer] for every entity and
 * counts its calls. It exists in the test source set only; the production adapter is INV-T-20226.
 */
class FixtureLastCompletePeriodPort : LastCompletePeriodPort {
    val calls = AtomicInteger()

    @Volatile var answer: String? = LAST_PERIOD

    @Volatile var unavailable: Boolean = false

    override fun lastCompletePeriod(
        tenant: TenantId,
        entityRef: String,
    ): String? {
        calls.incrementAndGet()
        if (unavailable) throw PeriodCalendarUnavailableException("the fixture calendar is switched off")
        return answer
    }

    fun reset() {
        calls.set(0)
        answer = LAST_PERIOD
        unavailable = false
    }

    companion object {
        /** The periods the fixture sample serves, oldest first; the last one is the last complete period. */
        val PERIODS = listOf("2026-Q1", "2026-Q2", "2026-Q3")
        val LAST_PERIOD = PERIODS.last()
    }
}
