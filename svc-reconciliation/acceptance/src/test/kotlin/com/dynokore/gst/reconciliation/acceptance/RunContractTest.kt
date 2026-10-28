package com.dynokore.gst.reconciliation.acceptance

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.reconciliation.domain.run.RunOutcome
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** INV-T-7246: the codes the run raises are error catalogue codes. */
class RunContractTest {
    @Test
    fun `INV-AC-669 PER_008 is a catalogue error code`() {
        ErrorCode.entries.map { it.code }.contains(RunOutcome.PER_008) shouldBe true
    }

    @Test
    fun `INV-AC-1807 PER_003 is a catalogue error code`() {
        ErrorCode.entries.map { it.code }.contains(RunOutcome.PER_003) shouldBe true
    }
}
