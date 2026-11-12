package com.dynokore.gst.rules.domain

import com.dynokore.gst.rules.domain.pack.ArmingGate
import com.dynokore.gst.rules.domain.pack.ArmingGate.Outcome
import com.dynokore.gst.rules.domain.pack.ArmingGate.Reason
import com.dynokore.gst.rules.domain.pack.PackStatus
import com.dynokore.gst.rules.domain.pack.RegulatoryCommand
import com.dynokore.gst.rules.domain.pack.RegulatoryPackLifecycle
import com.dynokore.gst.rules.domain.pack.RegulatoryPackLifecycle.Transition
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RegulatoryPackLifecycleTest {
    @Test
    fun `INV-AC-20459 the table holds exactly one transition and every other pair is refused`() {
        for (s in PackStatus.entries) {
            for (c in RegulatoryCommand.entries) {
                val expected =
                    if (s == PackStatus.STAGED && c == RegulatoryCommand.APPROVE) {
                        Transition.To(PackStatus.APPROVED)
                    } else {
                        Transition.Refused(s, c)
                    }
                RegulatoryPackLifecycle.next(s, c) shouldBe expected
            }
        }
        val transitions =
            PackStatus.entries
                .flatMap { s -> RegulatoryCommand.entries.map { c -> RegulatoryPackLifecycle.next(s, c) } }
                .filterIsInstance<Transition.To>()
        transitions.size shouldBe 1
    }

    @Test
    fun `INV-AC-20459 arming a STAGED pack is refused as no dry run without a delta`() {
        ArmingGate.check(PackStatus.STAGED, false) shouldBe Outcome.Refused(Reason.NO_DRY_RUN)
    }

    @Test
    fun `INV-AC-20459 arming a STAGED pack with a delta is refused as not approved`() {
        ArmingGate.check(PackStatus.STAGED, true) shouldBe Outcome.Refused(Reason.NOT_APPROVED)
    }

    @Test
    fun `INV-AC-20459 arming passes to the fence for APPROVED whatever the delta flag`() {
        ArmingGate.check(PackStatus.APPROVED, true) shouldBe Outcome.PassedToFence
        ArmingGate.check(PackStatus.APPROVED, false) shouldBe Outcome.PassedToFence
    }

    @Test
    fun `INV-AC-20459 arming every other status is not approved, ACKNOWLEDGED included`() {
        for (s in PackStatus.entries.filter { it != PackStatus.APPROVED && it != PackStatus.STAGED }) {
            for (d in listOf(true, false)) {
                ArmingGate.check(s, d) shouldBe Outcome.Refused(Reason.NOT_APPROVED)
            }
        }
        ArmingGate.check(PackStatus.ACKNOWLEDGED, true) shouldBe Outcome.Refused(Reason.NOT_APPROVED)
        RegulatoryPackLifecycle.next(PackStatus.ACKNOWLEDGED, RegulatoryCommand.ARM) shouldBe
            Transition.Refused(PackStatus.ACKNOWLEDGED, RegulatoryCommand.ARM)
    }
}
