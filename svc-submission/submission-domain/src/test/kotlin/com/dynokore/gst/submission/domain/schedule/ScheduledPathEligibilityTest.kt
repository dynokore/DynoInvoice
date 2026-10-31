package com.dynokore.gst.submission.domain.schedule

import com.dynokore.gst.submission.domain.schedule.ScheduleEligibility.Eligible
import com.dynokore.gst.submission.domain.schedule.ScheduleEligibility.NotEligible
import com.dynokore.gst.submission.domain.schedule.ScheduleExclusion.NETWORK_REAL_TIME
import com.dynokore.gst.submission.domain.schedule.ScheduleExclusion.TYPE_UNDECIDED
import com.dynokore.gst.submission.domain.typing.SubmissionType
import com.dynokore.gst.submission.domain.typing.SubmissionType.TYPE_1A
import com.dynokore.gst.submission.domain.typing.SubmissionType.TYPE_1B
import com.dynokore.gst.submission.domain.typing.SubmissionType.TYPE_2
import com.dynokore.gst.submission.domain.typing.SubmissionType.TYPE_3
import com.dynokore.gst.submission.domain.typing.TypeDecision.Decided
import com.dynokore.gst.submission.domain.typing.TypeDecision.Undecided
import com.dynokore.gst.submission.domain.typing.UndecidedReason
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ScheduledPathEligibilityTest {
    @Test
    fun `INV-AC-13804 each of the seven inputs gives its eligibility`() {
        ScheduledPathEligibility.decide(Decided(TYPE_1A)) shouldBe NotEligible(NETWORK_REAL_TIME)
        ScheduledPathEligibility.decide(Decided(TYPE_1B)) shouldBe Eligible(TYPE_1B)
        ScheduledPathEligibility.decide(Decided(TYPE_2)) shouldBe Eligible(TYPE_2)
        ScheduledPathEligibility.decide(Decided(TYPE_3)) shouldBe Eligible(TYPE_3)
        UndecidedReason.entries.forEach {
            ScheduledPathEligibility.decide(Undecided(it)) shouldBe NotEligible(TYPE_UNDECIDED)
        }
    }

    @Test
    fun `INV-AC-13804 the eligible types are exactly 1B, 2 and 3`() {
        SubmissionType.entries
            .filter { ScheduledPathEligibility.decide(Decided(it)) is Eligible }
            .toSet() shouldBe setOf(TYPE_1B, TYPE_2, TYPE_3)
    }

    @Test
    fun `submission types are pinned`() {
        SubmissionType.entries.map { it.name } shouldBe listOf("TYPE_1A", "TYPE_1B", "TYPE_2", "TYPE_3")
    }

    @Test
    fun `schedule exclusions are pinned`() {
        ScheduleExclusion.entries.map { it.name } shouldBe listOf("NETWORK_REAL_TIME", "TYPE_UNDECIDED")
    }
}
