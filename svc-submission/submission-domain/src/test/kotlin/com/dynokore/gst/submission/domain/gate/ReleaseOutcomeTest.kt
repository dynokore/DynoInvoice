package com.dynokore.gst.submission.domain.gate

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ReleaseOutcomeTest {
    private val unreadable = ReleaseOutcome.Denied(ReleaseOutcome.UNREADABLE)

    @Test
    fun `ALLOW RELEASED is Released`() {
        ReleaseOutcome.from("ALLOW", "RELEASED", null) shouldBe ReleaseOutcome.Released
    }

    @Test
    fun `ALLOW AWAITS_SECOND_APPROVER is AwaitsSecond`() {
        ReleaseOutcome.from("ALLOW", "AWAITS_SECOND_APPROVER", null) shouldBe ReleaseOutcome.AwaitsSecond
    }

    @Test
    fun `DENY carries its reason code`() {
        ReleaseOutcome.from("DENY", null, "SAME_PERSON_TWICE") shouldBe ReleaseOutcome.Denied("SAME_PERSON_TWICE")
        ReleaseOutcome.from("DENY", null, null) shouldBe ReleaseOutcome.Denied(ReleaseOutcome.UNSPECIFIED)
        ReleaseOutcome.from("DENY", "RELEASED", " ") shouldBe ReleaseOutcome.Denied(ReleaseOutcome.UNSPECIFIED)
    }

    @Test
    fun `probe outcome RELEASED with a trailing space is Denied`() {
        ReleaseOutcome.from("ALLOW", "RELEASED ", null) shouldBe unreadable
    }

    @Test
    fun `probe outcome released in lower case is Denied`() {
        ReleaseOutcome.from("ALLOW", "released", null) shouldBe unreadable
    }

    @Test
    fun `probe decision ALLOW with a trailing space is Denied`() {
        ReleaseOutcome.from("ALLOW ", "RELEASED", null) shouldBe unreadable
    }

    @Test
    fun `probe ALLOW without an outcome is Denied`() {
        ReleaseOutcome.from("ALLOW", null, null) shouldBe unreadable
    }

    @Test
    fun `probe an unknown decision or outcome is Denied`() {
        ReleaseOutcome.from("MAYBE", "RELEASED", null) shouldBe unreadable
        ReleaseOutcome.from(null, "RELEASED", null) shouldBe unreadable
        ReleaseOutcome.from("ALLOW", "OTHER", null) shouldBe unreadable
        ReleaseOutcome.from("deny", null, "X") shouldBe unreadable
    }
}
