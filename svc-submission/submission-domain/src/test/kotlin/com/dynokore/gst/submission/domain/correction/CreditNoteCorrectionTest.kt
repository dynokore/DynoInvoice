package com.dynokore.gst.submission.domain.correction

import com.dynokore.gst.submission.domain.correction.CorrectionDecision.NotSubmittable
import com.dynokore.gst.submission.domain.correction.CorrectionDecision.SubmitAsPosted
import com.dynokore.gst.submission.domain.correction.CorrectionDecision.SubmitSeparately
import com.dynokore.gst.submission.domain.correction.CorrectionDecision.Wait
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.ordering.LifecycleState.ACKNOWLEDGED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.CANCELLED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.DEAD_LETTERED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.EXTERNALLY_SUBMITTED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.NETWORK_ISSUED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.NOT_SUBMITTABLE
import com.dynokore.gst.submission.domain.ordering.LifecycleState.QUEUED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.READY
import com.dynokore.gst.submission.domain.ordering.LifecycleState.REJECTED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.RETRY
import com.dynokore.gst.submission.domain.ordering.LifecycleState.SENT
import com.dynokore.gst.submission.domain.ordering.LifecycleState.SUPERSEDED
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.File

class CreditNoteCorrectionTest {
    private fun decide(
        versions: List<LifecycleState>,
        declared: Boolean = false,
    ) = CreditNoteCorrection.decide(versions, declared)

    private val netted = NotSubmittable(CorrectionReason.NETTED_INTO_REVISION)

    @Test
    fun `INV-AC-603 acknowledged original without declaration is submitted separately`() {
        decide(listOf(ACKNOWLEDGED)) shouldBe SubmitSeparately(false)
    }

    @Test
    fun `INV-AC-2786 ready original without declaration is submitted as posted, also not submittable and none`() {
        decide(listOf(READY)) shouldBe SubmitAsPosted
        decide(listOf(NOT_SUBMITTABLE)) shouldBe SubmitAsPosted
        decide(emptyList()) shouldBe SubmitAsPosted
    }

    @Test
    fun `INV-AC-1764 cancelled and ready with declaration is not submittable as netted`() {
        decide(listOf(CANCELLED, READY), true) shouldBe netted
    }

    @Test
    fun `INV-AC-602 cancelled and queued with declaration is netted, without declaration it waits`() {
        decide(listOf(CANCELLED, QUEUED), true) shouldBe netted
        decide(listOf(CANCELLED, QUEUED), false) shouldBe Wait
    }

    @Test
    fun `INV-AC-1765 rejected only original counts as not submitted`() {
        decide(listOf(REJECTED)) shouldBe SubmitAsPosted
        OriginalStanding.of(listOf(REJECTED)) shouldBe Standing.NOT_SUBMITTED
        (decide(listOf(REJECTED), true) is NotSubmittable) shouldBe true
    }

    @ParameterizedTest
    @EnumSource(LifecycleState::class)
    fun `INV-AC-23340 every state as the single version has its class and standing`(state: LifecycleState) {
        val atIras = setOf(ACKNOWLEDGED, EXTERNALLY_SUBMITTED, SUPERSEDED)
        val sentOut = setOf(SENT, LifecycleState.SENT_UNCONFIRMED, RETRY, NETWORK_ISSUED)
        val expectedClass =
            when (state) {
                in atIras -> StateClass.AT_IRAS
                in sentOut -> StateClass.SENT_OUT
                QUEUED -> StateClass.QUEUED_CLASS
                else -> StateClass.NONE
            }
        val expectedStanding =
            when (expectedClass) {
                StateClass.AT_IRAS -> Standing.SUBMITTED
                StateClass.SENT_OUT, StateClass.QUEUED_CLASS -> Standing.IN_FLIGHT
                StateClass.NONE -> Standing.NOT_SUBMITTED
            }
        OriginalStanding.classOf(state) shouldBe expectedClass
        OriginalStanding.of(listOf(state)) shouldBe expectedStanding
        LifecycleState.entries.count { OriginalStanding.classOf(it) == StateClass.AT_IRAS } shouldBe 3
        LifecycleState.entries.count { OriginalStanding.classOf(it) == StateClass.SENT_OUT } shouldBe 4
        LifecycleState.entries.count { OriginalStanding.classOf(it) == StateClass.QUEUED_CLASS } shouldBe 1
        LifecycleState.entries.count { OriginalStanding.classOf(it) == StateClass.NONE } shouldBe 10
    }

    @Test
    fun `INV-AC-23340 empty list stands as not submitted`() {
        OriginalStanding.of(emptyList()) shouldBe Standing.NOT_SUBMITTED
    }

    @Test
    fun `INV-AC-23340 classified states equal the state names of x-lifecycle-states`() {
        val dir = System.getProperty("gst.contract.dir") ?: error("gap: system property gst.contract.dir is absent")
        val text = File(dir, "schemas/lifecycle-states.schema.json").readText()
        val catalogue = text.substringAfterLast("\"x-lifecycle-states\": [")
        val names = Regex("\"state\"\\s*:\\s*\"([A-Z_]+)\"").findAll(catalogue).map { it.groupValues[1] }.toList()
        names.size shouldBe 18
        names shouldContainExactlyInAnyOrder LifecycleState.entries.map { it.name }
        LifecycleState.entries.map { OriginalStanding.classOf(it) }.size shouldBe names.size
    }

    @Test
    fun `INV-AC-23340 row a acknowledged with declaration is submitted separately with the declaration ignored`() {
        decide(listOf(ACKNOWLEDGED), true) shouldBe SubmitSeparately(true)
    }

    @Test
    fun `INV-AC-23340 row b sent with declaration waits`() {
        decide(listOf(SENT), true) shouldBe Wait
    }

    @Test
    fun `INV-AC-23340 row c cancelled and ready with declaration is netted`() {
        decide(listOf(CANCELLED, READY), true) shouldBe netted
    }

    @Test
    fun `INV-AC-23340 row d queued without declaration waits`() {
        decide(listOf(QUEUED)) shouldBe Wait
    }

    @Test
    fun `INV-AC-23340 row e ready without declaration is submitted as posted`() {
        decide(listOf(READY)) shouldBe SubmitAsPosted
    }

    @Test
    fun `INV-AC-23340 sent and retry with declaration wait`() {
        decide(listOf(SENT), true) shouldBe Wait
        decide(listOf(RETRY), true) shouldBe Wait
        decide(listOf(LifecycleState.SENT_UNCONFIRMED), true) shouldBe Wait
    }

    @Test
    fun `INV-AC-23340 network issued waits with or without declaration and never posts or nets`() {
        decide(listOf(NETWORK_ISSUED), false) shouldBe Wait
        decide(listOf(NETWORK_ISSUED), true) shouldBe Wait
        decide(listOf(NETWORK_ISSUED, READY), true) shouldBe Wait
    }

    @Test
    fun `INV-AC-23340 superseded and externally submitted are submitted separately`() {
        decide(listOf(SUPERSEDED)) shouldBe SubmitSeparately(false)
        decide(listOf(EXTERNALLY_SUBMITTED)) shouldBe SubmitSeparately(false)
    }

    @Test
    fun `INV-AC-23340 any version counts whatever the order`() {
        decide(listOf(REJECTED, ACKNOWLEDGED)) shouldBe SubmitSeparately(false)
        decide(listOf(ACKNOWLEDGED, REJECTED)) shouldBe SubmitSeparately(false)
    }

    @Test
    fun `INV-AC-23340 submitted wins over sent out`() {
        decide(listOf(ACKNOWLEDGED, SENT), true) shouldBe SubmitSeparately(true)
        decide(listOf(SENT, ACKNOWLEDGED)) shouldBe SubmitSeparately(false)
    }

    @Test
    fun `INV-AC-23340 submitted wins over in flight`() {
        decide(listOf(ACKNOWLEDGED, QUEUED)) shouldBe SubmitSeparately(false)
        OriginalStanding.of(listOf(QUEUED, ACKNOWLEDGED)) shouldBe Standing.SUBMITTED
    }

    @Test
    fun `INV-AC-23340 dead lettered without declaration is submitted as posted`() {
        decide(listOf(DEAD_LETTERED)) shouldBe SubmitAsPosted
    }

    @Test
    fun `INV-AC-23340 queued and sent with declaration wait`() {
        decide(listOf(QUEUED, SENT), true) shouldBe Wait
    }

    @Test
    fun `INV-AC-23340 reason carries its string and decision is deterministic`() {
        CorrectionReason.NETTED_INTO_REVISION.code shouldBe "NETTED_INTO_REVISION"
        decide(listOf(CANCELLED, QUEUED), true) shouldBe decide(listOf(CANCELLED, QUEUED), true)
    }
}
