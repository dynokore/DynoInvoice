package com.dynokore.gst.submission.domain.routing

import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.ordering.LifecycleState.ACKNOWLEDGED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.AWAITING_PRECEDING
import com.dynokore.gst.submission.domain.ordering.LifecycleState.CANCELLED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.DEAD_LETTERED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.QUEUED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.READY
import com.dynokore.gst.submission.domain.ordering.LifecycleState.REJECTED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.RETRY
import com.dynokore.gst.submission.domain.ordering.LifecycleState.SENT
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class ArrivalRoutingTest {
    private fun input(
        creditNote: Boolean = false,
        periodFiled: Boolean = false,
        pre: Boolean = false,
        earlier: List<LifecycleState> = emptyList(),
        change: ChangeClass? = if (earlier.isEmpty()) null else ChangeClass.NON_MONETARY,
        type2: Boolean = false,
    ) = ArrivalInput(creditNote, periodFiled, pre, earlier, change, type2)

    private fun routed(
        timing: Timing,
        ordering: Ordering,
        amendment: Amendment,
        codes: List<ArrivalCode> = emptyList(),
        warnings: List<ArrivalWarning> = emptyList(),
    ) = Routed(timing, ordering, amendment, codes, warnings)

    private val routine = routed(Timing.ROUTINE, Ordering.NORMAL, Amendment.SUPERSEDE)
    private val after = routed(Timing.ROUTINE, Ordering.NORMAL, Amendment.AFTER_TRANSMISSION)

    @Test
    fun `INV-AC-1042 a first version in a filed period is immediate with SUB_010`() {
        ArrivalRouting.decide(input(periodFiled = true)) shouldBe
            routed(Timing.IMMEDIATE, Ordering.NORMAL, Amendment.NONE, listOf(ArrivalCode.SUB_010))
    }

    @Test
    fun `INV-AC-1042 a first version in an open period is routine with no code`() {
        ArrivalRouting.decide(input()) shouldBe routed(Timing.ROUTINE, Ordering.NORMAL, Amendment.NONE)
    }

    @Test
    fun `INV-AC-1042 a filed period with SENT and MONETARY keeps IMMEDIATE and SUB_010`() {
        ArrivalRouting.decide(
            input(periodFiled = true, earlier = listOf(SENT), change = ChangeClass.MONETARY),
        ) shouldBe
            routed(Timing.IMMEDIATE, Ordering.NORMAL, Amendment.AFTER_TRANSMISSION, listOf(ArrivalCode.SUB_010))
    }

    @Test
    fun `INV-AC-355 a credit note on a PRE_OBLIGATION original is standalone with PER_007`() {
        ArrivalRouting.decide(input(creditNote = true, pre = true)) shouldBe
            routed(Timing.ROUTINE, Ordering.STANDALONE, Amendment.NONE, listOf(ArrivalCode.PER_007))
    }

    @Test
    fun `INV-AC-355 look-alikes without both facts are normal with no PER_007`() {
        val normal = routed(Timing.ROUTINE, Ordering.NORMAL, Amendment.NONE)
        ArrivalRouting.decide(input(creditNote = true, pre = false)) shouldBe normal
        ArrivalRouting.decide(input(creditNote = false, pre = true)) shouldBe normal
    }

    @Test
    fun `INV-AC-355 a filed period lists SUB_010 before PER_007`() {
        ArrivalRouting.decide(input(creditNote = true, pre = true, periodFiled = true)) shouldBe
            routed(
                Timing.IMMEDIATE,
                Ordering.STANDALONE,
                Amendment.NONE,
                listOf(ArrivalCode.SUB_010, ArrivalCode.PER_007),
            )
    }

    @Test
    fun `INV-AC-615 QUEUED with MONETARY supersedes and warns only after a Type 2 rendition`() {
        ArrivalRouting.decide(input(earlier = listOf(QUEUED), change = ChangeClass.MONETARY)) shouldBe routine
        ArrivalRouting.decide(input(earlier = listOf(QUEUED), change = ChangeClass.MONETARY, type2 = true)) shouldBe
            routed(
                Timing.ROUTINE,
                Ordering.NORMAL,
                Amendment.SUPERSEDE,
                warnings = listOf(ArrivalWarning.TYPE2_RENDITION_ISSUED),
            )
    }

    @Test
    fun `INV-AC-615 the other non-structural classes supersede without a warning`() {
        listOf(ChangeClass.NON_MONETARY, ChangeClass.IDENTITY, ChangeClass.PERIOD_SHIFTING).forEach {
            ArrivalRouting.decide(input(earlier = listOf(QUEUED), change = it, type2 = true)) shouldBe routine
        }
    }

    @Test
    fun `INV-AC-615 SENT with MONETARY after a Type 2 rendition is after transmission with no warning`() {
        ArrivalRouting.decide(
            input(earlier = listOf(SENT), change = ChangeClass.MONETARY, type2 = true),
        ) shouldBe after
    }

    @Test
    fun `INV-AC-615 STRUCTURAL is rejected with ING-012 whatever the earlier versions`() {
        listOf(QUEUED, SENT).forEach {
            ArrivalRouting.decide(input(earlier = listOf(it), change = ChangeClass.STRUCTURAL)) shouldBe
                Rejected(RejectionCode.ING_012)
        }
        RejectionCode.ING_012.code shouldBe "ING-012"
        ArrivalCode.SUB_010.code shouldBe "SUB-010"
        ArrivalCode.PER_007.code shouldBe "PER-007"
    }

    @ParameterizedTest
    @EnumSource(LifecycleState::class)
    fun `INV-AC-615 every state as the single earlier version is transmitted in exactly eight`(state: LifecycleState) {
        val transmitted =
            setOf(
                LifecycleState.SENT,
                LifecycleState.SENT_UNCONFIRMED,
                LifecycleState.RETRY,
                LifecycleState.DEAD_LETTERED,
                LifecycleState.ACKNOWLEDGED,
                LifecycleState.SUPERSEDED,
                LifecycleState.NETWORK_ISSUED,
                LifecycleState.EXTERNALLY_SUBMITTED,
            )
        ArrivalRouting.decide(input(earlier = listOf(state))) shouldBe if (state in transmitted) after else routine
    }

    @Test
    fun `INV-AC-615 boundary mixes of earlier versions`() {
        ArrivalRouting.decide(input(earlier = listOf(REJECTED))) shouldBe routine
        ArrivalRouting.decide(input(earlier = listOf(REJECTED, ACKNOWLEDGED))) shouldBe after
        ArrivalRouting.decide(input(earlier = listOf(ACKNOWLEDGED, REJECTED))) shouldBe after
        ArrivalRouting.decide(input(earlier = listOf(CANCELLED, QUEUED))) shouldBe routine
        ArrivalRouting.decide(input(earlier = listOf(RETRY))) shouldBe after
        ArrivalRouting.decide(input(earlier = listOf(DEAD_LETTERED))) shouldBe after
    }

    @Test
    fun `INV-AC-615 an inconsistent changeClass is refused`() {
        assertThrows<IllegalArgumentException> { ArrivalRouting.decide(input(change = ChangeClass.MONETARY)) }
        assertThrows<IllegalArgumentException> { ArrivalRouting.decide(input(earlier = listOf(QUEUED), change = null)) }
    }

    @Test
    fun `INV-AC-1763 a revision over an untransmitted original supersedes it`() {
        listOf(QUEUED, READY, AWAITING_PRECEDING).forEach {
            ArrivalRouting.decide(input(earlier = listOf(it))) shouldBe routine
        }
    }

    @Test
    fun `INV-AC-619 a revision over a transmitted original is queued after it`() {
        listOf(SENT, ACKNOWLEDGED).forEach {
            ArrivalRouting.decide(input(earlier = listOf(it))) shouldBe after
        }
    }
}
