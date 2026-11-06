package com.dynokore.gst.submission.domain.gate

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ReleaseOutcomeTest {
    private val notOverridable = ReleaseOutcome.Denied(ReleaseOutcome.NOT_OVERRIDABLE)

    @Test
    fun `ALLOW RELEASED is Released`() {
        ReleaseOutcome.from("ALLOW", "RELEASED", null) shouldBe ReleaseOutcome.Released
    }

    @Test
    fun `ALLOW AWAITS_SECOND_APPROVER is AwaitsSecond`() {
        ReleaseOutcome.from("ALLOW", "AWAITS_SECOND_APPROVER", null) shouldBe ReleaseOutcome.AwaitsSecond
    }

    @Test
    fun `DENY with a reason code carries it unchanged`() {
        ReleaseOutcome.from("DENY", null, "SAME_PERSON_TWICE") shouldBe ReleaseOutcome.Denied("SAME_PERSON_TWICE")
        ReleaseOutcome.from("DENY", null, "NO_SOLE_OPERATOR_ACK") shouldBe ReleaseOutcome.Denied("NO_SOLE_OPERATOR_ACK")
    }

    @Test
    fun `DENY without a reason code gives NOT_OVERRIDABLE`() {
        ReleaseOutcome.from("DENY", null, null) shouldBe ReleaseOutcome.Denied("NOT_OVERRIDABLE")
    }

    @Test
    fun `DENY with a blank reason code gives NOT_OVERRIDABLE`() {
        ReleaseOutcome.from("DENY", "RELEASED", "  ") shouldBe ReleaseOutcome.Denied("NOT_OVERRIDABLE")
    }

    @Test
    fun `probe outcome RELEASED with a trailing space is Denied`() {
        ReleaseOutcome.from("ALLOW", "RELEASED ", null) shouldBe notOverridable
    }

    @Test
    fun `probe outcome released in lower case is Denied`() {
        ReleaseOutcome.from("ALLOW", "released", null) shouldBe notOverridable
    }

    @Test
    fun `probe decision ALLOW with a trailing space is Denied`() {
        ReleaseOutcome.from("ALLOW ", "RELEASED", null) shouldBe notOverridable
    }

    @Test
    fun `probe ALLOW without an outcome is Denied`() {
        ReleaseOutcome.from("ALLOW", null, null) shouldBe notOverridable
    }

    @Test
    fun `probe an unknown decision or outcome is Denied`() {
        ReleaseOutcome.from("MAYBE", "RELEASED", null) shouldBe notOverridable
        ReleaseOutcome.from(null, "RELEASED", null) shouldBe notOverridable
        ReleaseOutcome.from("ALLOW", "OTHER", null) shouldBe notOverridable
        ReleaseOutcome.from("deny", null, "X") shouldBe notOverridable
    }
}
