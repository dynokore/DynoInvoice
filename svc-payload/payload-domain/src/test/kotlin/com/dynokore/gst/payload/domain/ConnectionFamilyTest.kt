package com.dynokore.gst.payload.domain

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ConnectionFamilyTest {
    private val pint = SpecificationFamily.PINT_SG
    private val bis = SpecificationFamily.SG_PEPPOL_BIS

    private fun refused(reason: ActivationRefusal) = ActivationVerdict.Refused(reason)

    @Test
    fun `INV-AC-2781 no election gives the default PINT_SG`() {
        FamilyElection.effective(null, pint) shouldBe pint
    }

    @Test
    fun `an election of SG_PEPPOL_BIS beats the default`() {
        FamilyElection.effective(bis, pint) shouldBe bis
    }

    @Test
    fun `an election of PINT_SG beats a different default`() {
        FamilyElection.effective(pint, bis) shouldBe pint
    }

    @Test
    fun `INV-AC-2543 a passed test in the effective family may activate`() {
        ConnectionActivation.decide(bis, ConnectionTestRun(bis, passed = true)) shouldBe
            ActivationVerdict.MayActivate
    }

    @Test
    fun `INV-AC-2543 a failed test in the effective family is refused as TEST_FAILED`() {
        ConnectionActivation.decide(bis, ConnectionTestRun(bis, passed = false)) shouldBe
            refused(ActivationRefusal.TEST_FAILED)
    }

    @Test
    fun `no test run is refused as TEST_NOT_RUN`() {
        ConnectionActivation.decide(bis, null) shouldBe refused(ActivationRefusal.TEST_NOT_RUN)
    }

    @Test
    fun `a passed test in another family is refused as TESTED_IN_OTHER_FAMILY`() {
        ConnectionActivation.decide(bis, ConnectionTestRun(pint, passed = true)) shouldBe
            refused(ActivationRefusal.TESTED_IN_OTHER_FAMILY)
    }

    @Test
    fun `a failed test in another family is refused as TESTED_IN_OTHER_FAMILY`() {
        ConnectionActivation.decide(bis, ConnectionTestRun(pint, passed = false)) shouldBe
            refused(ActivationRefusal.TESTED_IN_OTHER_FAMILY)
    }
}
