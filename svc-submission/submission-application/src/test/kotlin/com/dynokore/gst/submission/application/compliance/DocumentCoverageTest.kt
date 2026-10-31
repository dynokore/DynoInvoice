package com.dynokore.gst.submission.application.compliance

import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.ordering.LifecycleState.ACKNOWLEDGED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.QUEUED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.REJECTED
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DocumentCoverageTest {
    private val unsubmitted =
        setOf(
            "INGESTED",
            "HELD",
            "BLOCKED",
            "READY",
            "AWAITING_PRECEDING",
            "QUEUED",
            "SENT",
            "SENT_UNCONFIRMED",
            "RETRY",
            "DEAD_LETTERED",
            "NETWORK_ISSUED",
            "REJECTED",
        )

    @Test
    fun `a single version has a submission exactly when its state is not in the unsubmitted set`() {
        LifecycleState.entries.size shouldBe 18
        LifecycleState.entries.forEach { state ->
            DocumentCoverage.hasSubmission(listOf(state)) shouldBe (state.name !in unsubmitted)
        }
    }

    @Test
    fun `a document with no version has no submission`() {
        DocumentCoverage.hasSubmission(emptyList()) shouldBe false
    }

    @Test
    fun `a rejected latest version counts as submitted only after an earlier acknowledged one`() {
        DocumentCoverage.hasSubmission(listOf(ACKNOWLEDGED, REJECTED)) shouldBe true
        DocumentCoverage.hasSubmission(listOf(QUEUED, REJECTED)) shouldBe false
        DocumentCoverage.hasSubmission(listOf(REJECTED)) shouldBe false
        DocumentCoverage.hasSubmission(listOf(ACKNOWLEDGED, QUEUED)) shouldBe false
    }
}
