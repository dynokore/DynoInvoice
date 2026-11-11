package com.dynokore.gst.submission.application.queue

import com.dynokore.gst.submission.domain.ordering.LifecycleState
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SubmissionFailureTest {
    private val manual = CatalogueEntry("SUB-003", "Platform", "MANUAL")
    private val noDisposition = CatalogueEntry("SUB-900", "Platform", null)

    @Test
    fun `INV-AC-20650 a rejected version with an entry carries the entry members and its disposition`() {
        SubmissionFailure.of("d", 1, LifecycleState.REJECTED, "SUB-003", manual) shouldBe
            SubmissionFailure("d", 1, "SUB-003", "MANUAL", "SUB-003", "Platform", "MANUAL")
    }

    @Test
    fun `INV-AC-20650 a rejected version without an entry is TERMINAL with no catalogue members`() {
        SubmissionFailure.of("d", 2, LifecycleState.REJECTED, "X-999", null) shouldBe
            SubmissionFailure("d", 2, "X-999", "TERMINAL", null, null, null)
    }

    @Test
    fun `INV-AC-20650 a rejected version whose entry has no disposition is TERMINAL without a disposition member`() {
        SubmissionFailure.of("d", 5, LifecycleState.REJECTED, "SUB-900", noDisposition) shouldBe
            SubmissionFailure("d", 5, "SUB-900", "TERMINAL", "SUB-900", "Platform", null)
    }

    @Test
    fun `INV-AC-20650 a dead-lettered version is MANUAL whatever the entry disposition`() {
        val retry = CatalogueEntry("SUB-001", "Platform", "AUTO_RETRY")
        SubmissionFailure.of("d", 3, LifecycleState.DEAD_LETTERED, "SUB-001", retry) shouldBe
            SubmissionFailure("d", 3, "SUB-001", "MANUAL", "SUB-001", "Platform", "AUTO_RETRY")
    }

    @Test
    fun `INV-AC-20650 any other state is refused`() {
        LifecycleState.entries
            .filter { it != LifecycleState.REJECTED && it != LifecycleState.DEAD_LETTERED }
            .forEach { state ->
                shouldThrow<IllegalArgumentException> { SubmissionFailure.of("d", 1, state, "SUB-003", manual) }
            }
    }
}
