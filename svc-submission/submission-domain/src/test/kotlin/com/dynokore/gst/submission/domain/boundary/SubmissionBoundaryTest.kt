package com.dynokore.gst.submission.domain.boundary

import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.ordering.LifecycleState.ACKNOWLEDGED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.AWAITING_PRECEDING
import com.dynokore.gst.submission.domain.ordering.LifecycleState.CANCELLED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.QUEUED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.READY
import com.dynokore.gst.submission.domain.ordering.LifecycleState.REJECTED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.SENT
import com.dynokore.gst.submission.domain.ordering.LifecycleState.SENT_UNCONFIRMED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.WITHDRAWN
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class SubmissionBoundaryTest {
    private val submitted =
        setOf(
            LifecycleState.SENT,
            LifecycleState.SENT_UNCONFIRMED,
            LifecycleState.RETRY,
            LifecycleState.DEAD_LETTERED,
            LifecycleState.REJECTED,
            LifecycleState.ACKNOWLEDGED,
            LifecycleState.SUPERSEDED,
            LifecycleState.NETWORK_ISSUED,
            LifecycleState.EXTERNALLY_SUBMITTED,
        )

    private fun uuid(n: Int) = UUID.fromString("00000000-0000-0000-0000-%012d".format(n))

    private fun group(vararg versions: List<LifecycleState>): Map<UUID, Collection<LifecycleState>> =
        versions.withIndex().associate { (i, v) -> uuid(i + 1) to v }

    @Test
    fun `INV-AC-13175 a group with one submitted document is refused and a group with none is allowed`() {
        val first = group(listOf(READY), listOf(QUEUED), listOf(SENT))
        val second = group(listOf(READY), listOf(AWAITING_PRECEDING), listOf(WITHDRAWN))
        val firstCopy = first.mapValues { it.value.toList() }
        val secondCopy = second.mapValues { it.value.toList() }

        val n1 = SubmissionBoundary.submittedCount(first)
        n1 shouldBe 1
        val refused = SubmissionBoundary.bulkResolveVerdict(n1)
        refused shouldBe BulkResolveVerdict.Refused(1)
        (refused as BulkResolveVerdict.Refused).submittedCount shouldBe 1
        refused.correctionPath shouldBe ReversibilityClass.COMPENSABLE

        val n2 = SubmissionBoundary.submittedCount(second)
        n2 shouldBe 0
        val allowed = SubmissionBoundary.bulkResolveVerdict(n2)
        allowed shouldBe BulkResolveVerdict.Allowed
        (allowed as BulkResolveVerdict.Allowed).reversibility shouldBe ReversibilityClass.UNDOABLE

        first shouldBe firstCopy
        second shouldBe secondCopy
    }

    @Test
    fun `INV-AC-13176 classOf is IRREVERSIBLE for the nine submitted states and UNDOABLE for the other nine`() {
        LifecycleState.values().forEach { s ->
            SubmissionBoundary.classOf(s) shouldBe
                if (s in submitted) ReversibilityClass.IRREVERSIBLE else ReversibilityClass.UNDOABLE
        }
        LifecycleState.values().count { SubmissionBoundary.classOf(it) == ReversibilityClass.IRREVERSIBLE } shouldBe 9
        LifecycleState.values().count { SubmissionBoundary.classOf(it) == ReversibilityClass.UNDOABLE } shouldBe 9
        SubmissionBoundary.CORRECTION_PATH shouldBe ReversibilityClass.COMPENSABLE
    }

    @Test
    fun `INV-AC-13177 submittedCount counts a Doc UUID once and an empty version list as not submitted`() {
        val g = group(listOf(SENT_UNCONFIRMED), listOf(ACKNOWLEDGED, READY), listOf(READY), emptyList())
        val copy = g.mapValues { it.value.toList() }
        SubmissionBoundary.submittedCount(g) shouldBe 2
        g shouldBe copy
    }

    @Test
    fun `a Doc UUID with several submitted versions counts once`() {
        SubmissionBoundary.submittedCount(group(listOf(SENT, REJECTED))) shouldBe 1
    }

    @Test
    fun `an empty map gives zero`() {
        SubmissionBoundary.submittedCount(emptyMap()) shouldBe 0
    }

    @Test
    fun `a Doc UUID with only unsubmitted versions is not submitted`() {
        SubmissionBoundary.submittedCount(group(listOf(READY, QUEUED, CANCELLED))) shouldBe 0
    }

    @Test
    fun `a negative submitted count is refused`() {
        assertThrows<IllegalArgumentException> { SubmissionBoundary.bulkResolveVerdict(-1) }
    }

    @Test
    fun `the submitted set is pinned and every state is decided`() {
        SubmissionBoundary.SUBMITTED_STATES shouldBe submitted
        LifecycleState.values().size shouldBe 18
        LifecycleState.values().forEach { SubmissionBoundary.isSubmitted(it) shouldBe (it in submitted) }
    }
}
