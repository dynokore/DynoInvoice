package com.dynokore.gst.submission.domain.ordering

import com.dynokore.gst.submission.domain.ordering.CreditNoteDecision.Hold
import com.dynokore.gst.submission.domain.ordering.CreditNoteDecision.HoldPastMargin
import com.dynokore.gst.submission.domain.ordering.CreditNoteDecision.Release
import com.dynokore.gst.submission.domain.ordering.CreditNoteDecision.ReleaseAlone
import com.dynokore.gst.submission.domain.ordering.CreditNoteOption.HOLD_FOR_APPROVER
import com.dynokore.gst.submission.domain.ordering.CreditNoteOption.RELEASE_ALONE
import com.dynokore.gst.submission.domain.ordering.LifecycleState.ACKNOWLEDGED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.AWAITING_PRECEDING
import com.dynokore.gst.submission.domain.ordering.LifecycleState.BLOCKED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.CANCELLED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.QUEUED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.REJECTED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.RETRY
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.File
import java.time.Instant

class CreditNoteOrderingTest {
    private val at = Instant.parse("2026-11-10T00:00:00Z")
    private val second = java.time.Duration.ofSeconds(1)

    private fun decide(
        preceding: List<LifecycleState>?,
        now: Instant = at.minus(second),
        option: CreditNoteOption = RELEASE_ALONE,
        link: String? = "doc-1",
    ) = CreditNoteOrdering.decide(CreditNoteOrderingInput(link, preceding, option, now, at))

    @Test
    fun `INV-AC-752 preceding QUEUED before the instant is held and released once ACKNOWLEDGED`() {
        decide(listOf(QUEUED)) shouldBe Hold("SUB-011")
        decide(listOf(ACKNOWLEDGED)) shouldBe Release(ReleaseReason.PRECEDING_AT_IRAS)
    }

    @Test
    fun `INV-AC-753 preceding RETRY at the instant has both documents in readiness under both options`() {
        decide(listOf(RETRY), at, RELEASE_ALONE) shouldBe ReleaseAlone("SUB-012", true)
        decide(listOf(RETRY), at, HOLD_FOR_APPROVER) shouldBe HoldPastMargin("SUB-012", true)
        decide(listOf(RETRY), at.minus(second), RELEASE_ALONE) shouldBe Hold("SUB-011")
        decide(listOf(RETRY), at.minus(second), HOLD_FOR_APPROVER) shouldBe Hold("SUB-011")
    }

    @Test
    fun `INV-AC-765 preceding BLOCKED at the instant is released alone or held past the margin by option`() {
        decide(listOf(BLOCKED), at, RELEASE_ALONE) shouldBe ReleaseAlone("SUB-012", true)
        decide(listOf(BLOCKED), at, HOLD_FOR_APPROVER) shouldBe HoldPastMargin("SUB-012", true)
    }

    @ParameterizedTest
    @EnumSource(LifecycleState::class)
    fun `every state as the single preceding version has its class`(state: LifecycleState) {
        val expected =
            when (state) {
                ACKNOWLEDGED, LifecycleState.EXTERNALLY_SUBMITTED, LifecycleState.SUPERSEDED -> StateClass.AT_IRAS
                LifecycleState.NOT_SUBMITTABLE, CANCELLED, LifecycleState.WITHDRAWN -> StateClass.NOT_GOING
                else -> StateClass.PENDING
            }
        CreditNoteOrdering.classify(listOf(state)) shouldBe expected
        LifecycleState.entries.count { CreditNoteOrdering.classify(it) == StateClass.AT_IRAS } shouldBe 3
        LifecycleState.entries.count { CreditNoteOrdering.classify(it) == StateClass.NOT_GOING } shouldBe 3
        LifecycleState.entries.count { CreditNoteOrdering.classify(it) == StateClass.PENDING } shouldBe 12
    }

    @Test
    fun `LifecycleState equals the union of the schema enums`() {
        val text = File(System.getProperty("gst.contract.dir"), "schemas/lifecycle-states.schema.json").readText()

        fun enumOf(def: String): List<String> {
            val block = Regex("\"$def\"\\s*:\\s*\\{[^\\[]*\"enum\"\\s*:\\s*\\[([^\\]]*)]").find(text)!!.groupValues[1]
            return Regex("\"([A-Z_]+)\"").findAll(block).map { it.groupValues[1] }.toList()
        }
        val union = enumOf("documentLevelState") + enumOf("versionLevelState")
        union.size shouldBe 18
        LifecycleState.entries.map { it.name } shouldContainExactlyInAnyOrder union
    }

    @Test
    fun `boundary one second before, equal to and one second after the instant`() {
        decide(listOf(QUEUED), at.minus(second)) shouldBe Hold("SUB-011")
        decide(listOf(QUEUED), at) shouldBe ReleaseAlone("SUB-012", true)
        decide(listOf(QUEUED), at.plus(second)) shouldBe ReleaseAlone("SUB-012", true)
        decide(listOf(QUEUED), at.plus(second), HOLD_FOR_APPROVER) shouldBe HoldPastMargin("SUB-012", true)
    }

    @Test
    fun `any version at IRAS counts whatever the order`() {
        decide(listOf(REJECTED, ACKNOWLEDGED)) shouldBe Release(ReleaseReason.PRECEDING_AT_IRAS)
        decide(listOf(ACKNOWLEDGED, REJECTED)) shouldBe Release(ReleaseReason.PRECEDING_AT_IRAS)
    }

    @Test
    fun `look-alikes pending, not going and absent`() {
        decide(listOf(REJECTED)) shouldBe Hold("SUB-011")
        decide(listOf(CANCELLED)) shouldBe Release(ReleaseReason.PRECEDING_NOT_GOING)
        decide(listOf(REJECTED, CANCELLED)) shouldBe Release(ReleaseReason.PRECEDING_NOT_GOING)
        decide(listOf(CANCELLED, REJECTED)) shouldBe Hold("SUB-011")
        decide(null) shouldBe Hold("SUB-011")
        decide(emptyList()) shouldBe Hold("SUB-011")
        decide(listOf(AWAITING_PRECEDING)) shouldBe Hold("SUB-011")
        decide(null, at) shouldBe ReleaseAlone("SUB-012", true)
    }

    @Test
    fun `no preceding link releases whatever the register says`() {
        decide(listOf(QUEUED), link = null) shouldBe Release(ReleaseReason.NO_PRECEDING_LINK)
        decide(null, at, link = null) shouldBe Release(ReleaseReason.NO_PRECEDING_LINK)
    }

    @Test
    fun `equal input gives equal output`() {
        decide(listOf(RETRY), at) shouldBe decide(listOf(RETRY), at)
    }
}
