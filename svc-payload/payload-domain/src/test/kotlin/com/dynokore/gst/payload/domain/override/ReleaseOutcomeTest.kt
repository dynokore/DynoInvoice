package com.dynokore.gst.payload.domain.override

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class ReleaseOutcomeTest {
    @Test
    fun `ALLOW with RELEASED is released`() {
        ReleaseOutcome.from("ALLOW", "RELEASED", null) shouldBe ReleaseOutcome.Released
    }

    @Test
    fun `ALLOW with AWAITS_SECOND_APPROVER awaits a second approver`() {
        ReleaseOutcome.from("ALLOW", "AWAITS_SECOND_APPROVER", null) shouldBe ReleaseOutcome.AwaitsSecond
    }

    @Test
    fun `DENY carries its reason code`() {
        ReleaseOutcome.from("DENY", null, "SAME_PERSON_TWICE") shouldBe ReleaseOutcome.Denied("SAME_PERSON_TWICE")
    }

    @Test
    fun `DENY without a reason code is denied as not overridable`() {
        ReleaseOutcome.from("DENY", null, null) shouldBe ReleaseOutcome.Denied(ReleaseOutcome.NOT_OVERRIDABLE)
        ReleaseOutcome.from("DENY", null, "  ") shouldBe ReleaseOutcome.Denied(ReleaseOutcome.NOT_OVERRIDABLE)
    }

    @Test
    fun `an outcome with a trailing space is denied`() {
        ReleaseOutcome.from("ALLOW", "RELEASED ", null) shouldBe ReleaseOutcome.Denied(ReleaseOutcome.NOT_OVERRIDABLE)
    }

    @Test
    fun `a lower case outcome is denied`() {
        ReleaseOutcome.from("ALLOW", "released", null) shouldBe ReleaseOutcome.Denied(ReleaseOutcome.NOT_OVERRIDABLE)
    }

    @Test
    fun `a decision with a trailing space is denied`() {
        ReleaseOutcome.from("ALLOW ", "RELEASED", null) shouldBe ReleaseOutcome.Denied(ReleaseOutcome.NOT_OVERRIDABLE)
    }

    @Test
    fun `ALLOW without an outcome is denied`() {
        ReleaseOutcome.from("ALLOW", null, null) shouldBe ReleaseOutcome.Denied(ReleaseOutcome.NOT_OVERRIDABLE)
    }

    @Test
    fun `an unknown or missing decision is denied`() {
        ReleaseOutcome.from("MAYBE", "RELEASED", null) shouldBe ReleaseOutcome.Denied(ReleaseOutcome.NOT_OVERRIDABLE)
        ReleaseOutcome.from(null, "RELEASED", null) shouldBe ReleaseOutcome.Denied(ReleaseOutcome.NOT_OVERRIDABLE)
    }

    @Test
    fun `an unknown outcome is denied`() {
        ReleaseOutcome.from("ALLOW", "SOMETHING_NEW", null) shouldBe
            ReleaseOutcome.Denied(ReleaseOutcome.NOT_OVERRIDABLE)
    }
}
