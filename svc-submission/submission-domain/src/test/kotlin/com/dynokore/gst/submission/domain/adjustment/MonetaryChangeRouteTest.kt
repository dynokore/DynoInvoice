package com.dynokore.gst.submission.domain.adjustment

import com.dynokore.gst.submission.domain.adjustment.ChangeOutcome.CREDIT_NOTE
import com.dynokore.gst.submission.domain.adjustment.ChangeOutcome.KEYING_CORRECTION
import com.dynokore.gst.submission.domain.adjustment.RouteChoice.Accepted
import com.dynokore.gst.submission.domain.adjustment.RouteChoice.Refused
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

private val WIN = RouteOption.KEYING_CORRECTION_WINDOW

class MonetaryChangeRouteTest {
    private val both = setOf(CREDIT_NOTE, KEYING_CORRECTION)
    private val ask = RouteSetting(RouteOption.ASK_APPROVER, null)
    private val always = RouteSetting(RouteOption.ALWAYS_CREDIT_NOTE, null)
    private val window = RouteSetting(WIN, 10)

    @Suppress("LongParameterList")
    private fun choose(
        option: RouteOption,
        days: Int?,
        allowed: Set<ChangeOutcome> = both,
        available: Boolean = true,
        min: Int = 1,
        max: Int = 30,
    ) = MonetaryChangeRoute.choose(option, days, allowed, available, min, max)

    @Test
    fun `INV-AC-16254 window refused while keying unavailable`() {
        choose(WIN, 10, available = false) shouldBe Refused(RouteRefusal.OPTION_UNAVAILABLE)
    }

    @Test
    fun `INV-AC-16254 only credit note offered while keying unavailable`() {
        for (s in listOf(ask, always, window)) {
            for (d in listOf(0, 400)) {
                MonetaryChangeRoute.offer(s, d, both, false) shouldBe setOf(CREDIT_NOTE)
            }
        }
    }

    @Test
    fun `INV-AC-16254 offers while keying available`() {
        MonetaryChangeRoute.offer(ask, 0, both, true) shouldBe both
        MonetaryChangeRoute.offer(always, 0, both, true) shouldBe setOf(CREDIT_NOTE)
        MonetaryChangeRoute.offer(window, 0, both, true) shouldBe both
        MonetaryChangeRoute.offer(window, 10, both, true) shouldBe both
        MonetaryChangeRoute.offer(window, 11, both, true) shouldBe setOf(CREDIT_NOTE)
    }

    @Test
    fun `INV-AC-16254 forbidden outcome never offered or accepted`() {
        val creditOnly = setOf(CREDIT_NOTE)
        for (s in listOf(ask, always, window)) {
            MonetaryChangeRoute.offer(s, 0, creditOnly, true) shouldBe creditOnly
        }
        choose(WIN, 10, creditOnly) shouldBe Refused(RouteRefusal.OUTCOME_FORBIDDEN)
        val keyingOnly = setOf(KEYING_CORRECTION)
        choose(RouteOption.ALWAYS_CREDIT_NOTE, null, keyingOnly) shouldBe Refused(RouteRefusal.OUTCOME_FORBIDDEN)
        MonetaryChangeRoute.offer(ask, 0, keyingOnly, true) shouldBe keyingOnly
        MonetaryChangeRoute.offer(ask, 0, keyingOnly, false) shouldBe emptySet()
        MonetaryChangeRoute.offer(window, 0, emptySet(), true) shouldBe emptySet()
    }

    @Test
    fun `INV-AC-16254 window rules of choose`() {
        choose(WIN, null) shouldBe Refused(RouteRefusal.WINDOW_REQUIRED)
        choose(WIN, 0) shouldBe Refused(RouteRefusal.WINDOW_OUT_OF_RANGE)
        choose(WIN, 31) shouldBe Refused(RouteRefusal.WINDOW_OUT_OF_RANGE)
        choose(WIN, 1) shouldBe Accepted(RouteSetting(WIN, 1))
        choose(WIN, 30) shouldBe Accepted(RouteSetting(WIN, 30))
        choose(RouteOption.ASK_APPROVER, 5) shouldBe Refused(RouteRefusal.WINDOW_NOT_ALLOWED)
        choose(RouteOption.ALWAYS_CREDIT_NOTE, 5) shouldBe Refused(RouteRefusal.WINDOW_NOT_ALLOWED)
        choose(RouteOption.ASK_APPROVER, null) shouldBe Accepted(MonetaryChangeRoute.DEFAULT)
        choose(RouteOption.ALWAYS_CREDIT_NOTE, null) shouldBe Accepted(always)
    }

    @Test
    fun `INV-AC-16254 invalid arguments throw`() {
        shouldThrow<IllegalArgumentException> { choose(RouteOption.ASK_APPROVER, null, min = 0, max = 30) }
        shouldThrow<IllegalArgumentException> { choose(RouteOption.ASK_APPROVER, null, min = 5, max = 4) }
        shouldThrow<IllegalArgumentException> { MonetaryChangeRoute.offer(ask, -1, both, true) }
        shouldThrow<IllegalArgumentException> { RouteSetting(WIN, null) }
        shouldThrow<IllegalArgumentException> { RouteSetting(WIN, 0) }
        shouldThrow<IllegalArgumentException> { RouteSetting(RouteOption.ASK_APPROVER, 3) }
    }

    @Test
    fun `INV-AC-16254 keys and members`() {
        for (o in RouteOption.entries) RouteOption.fromKey(o.name) shouldBe o
        for (k in listOf("ask_approver", "ASK_APPROVER ", "")) {
            shouldThrow<IllegalArgumentException> { RouteOption.fromKey(k) }
        }
        RouteOption.entries.map { it.name } shouldBe
            listOf("ASK_APPROVER", "ALWAYS_CREDIT_NOTE", "KEYING_CORRECTION_WINDOW")
        ChangeOutcome.entries.map { it.name } shouldBe listOf("CREDIT_NOTE", "KEYING_CORRECTION")
    }

    @Test
    fun `INV-AC-16254 same call twice gives equal results`() {
        choose(WIN, 7) shouldBe choose(WIN, 7)
        MonetaryChangeRoute.offer(window, 3, both, true) shouldBe MonetaryChangeRoute.offer(window, 3, both, true)
    }
}
