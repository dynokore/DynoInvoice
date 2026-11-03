package com.dynokore.gst.canonical.domain.derivation

import com.dynokore.gst.canonical.domain.document.ScopeVerdict
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class FunctionScopeGuardTest {
    @Test
    fun `INV-AC-15891 the nine pairs of function and line verdict give the expected verdict`() {
        for (function in UpstreamFunction.entries) {
            for (byLines in ScopeVerdict.entries) {
                val expected = if (function == UpstreamFunction.NOT_A_BILL) ScopeVerdict.OUT_OF_SCOPE else byLines
                FunctionScopeGuard.decide(function, byLines) shouldBe expected
            }
        }
        (UpstreamFunction.entries.size * ScopeVerdict.entries.size) shouldBe 9
    }

    @Test
    fun `INV-AC-15892 an advance payment record is out of scope and the tax invoice keeps its line verdict`() {
        val payment = FunctionScopeGuard.decide(UpstreamFunction.NOT_A_BILL, ScopeVerdict.IN_SCOPE)
        val taxInvoice = FunctionScopeGuard.decide(UpstreamFunction.BILL_FOR_PAYMENT, ScopeVerdict.IN_SCOPE)
        payment shouldBe ScopeVerdict.OUT_OF_SCOPE
        taxInvoice shouldBe ScopeVerdict.IN_SCOPE
    }

    @Test
    fun `INV-AC-15891 the enum members are exactly the classified ones so a new member fails here`() {
        UpstreamFunction.entries.map { it.name } shouldBe listOf("BILL_FOR_PAYMENT", "ADJUSTMENT", "NOT_A_BILL")
        ScopeVerdict.entries.map { it.name } shouldBe listOf("IN_SCOPE", "EXCLUDED_TXN", "OUT_OF_SCOPE")
    }
}
