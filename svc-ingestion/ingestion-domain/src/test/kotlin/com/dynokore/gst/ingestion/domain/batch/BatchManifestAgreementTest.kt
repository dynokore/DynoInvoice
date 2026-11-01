package com.dynokore.gst.ingestion.domain.batch

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class BatchManifestAgreementTest {
    private val contents = BatchContents(3, BigDecimal("300.00"), BigDecimal("21.00"))

    private fun declared(
        count: Long? = 3,
        net: String? = "300.00",
        tax: String? = "21.00",
    ) = BatchDeclaration(count, net?.let(::BigDecimal), tax?.let(::BigDecimal))

    private fun refused(vararg m: ManifestMismatch) = BatchManifestVerdict.Refused(m.toList())

    @Test
    fun `INV-AC-14105 all three equal gives Agrees`() {
        BatchManifestAgreement.check(declared(), contents) shouldBe BatchManifestVerdict.Agrees
    }

    @Test
    fun `INV-AC-14105 declared net 300_0 against 300_00 gives Agrees by numeric comparison`() {
        BatchManifestAgreement.check(declared(net = "300.0"), contents) shouldBe BatchManifestVerdict.Agrees
    }

    @Test
    fun `INV-AC-14105 declared count 3 against contents 4 differs on the count only`() {
        BatchManifestAgreement.check(declared(), contents.copy(count = 4)) shouldBe
            refused(ManifestMismatch(ManifestField.COUNT, ManifestFault.DIFFERS))
    }

    @Test
    fun `INV-AC-14105 declared count 4 against contents 3 differs on the count only`() {
        BatchManifestAgreement.check(declared(count = 4), contents) shouldBe
            refused(ManifestMismatch(ManifestField.COUNT, ManifestFault.DIFFERS))
    }

    @Test
    fun `INV-AC-14105 net 300_00 against 300_01 differs on the net total only`() {
        BatchManifestAgreement.check(declared(), contents.copy(netTotal = BigDecimal("300.01"))) shouldBe
            refused(ManifestMismatch(ManifestField.NET_TOTAL, ManifestFault.DIFFERS))
    }

    @Test
    fun `INV-AC-14105 tax 21_00 against 20_99 differs on the tax total only`() {
        BatchManifestAgreement.check(declared(), contents.copy(taxTotal = BigDecimal("20.99"))) shouldBe
            refused(ManifestMismatch(ManifestField.TAX_TOTAL, ManifestFault.DIFFERS))
    }

    @Test
    fun `INV-AC-14105 an absent count alone is MISSING`() {
        BatchManifestAgreement.check(declared(count = null), contents) shouldBe
            refused(ManifestMismatch(ManifestField.COUNT, ManifestFault.MISSING))
    }

    @Test
    fun `INV-AC-14105 an absent net total alone is MISSING`() {
        BatchManifestAgreement.check(declared(net = null), contents) shouldBe
            refused(ManifestMismatch(ManifestField.NET_TOTAL, ManifestFault.MISSING))
    }

    @Test
    fun `INV-AC-14105 an absent tax total alone is MISSING`() {
        BatchManifestAgreement.check(declared(tax = null), contents) shouldBe
            refused(ManifestMismatch(ManifestField.TAX_TOTAL, ManifestFault.MISSING))
    }

    @Test
    fun `INV-AC-14105 all three absent give three entries in the order count net tax`() {
        BatchManifestAgreement.check(declared(null, null, null), contents) shouldBe
            refused(
                ManifestMismatch(ManifestField.COUNT, ManifestFault.MISSING),
                ManifestMismatch(ManifestField.NET_TOTAL, ManifestFault.MISSING),
                ManifestMismatch(ManifestField.TAX_TOTAL, ManifestFault.MISSING),
            )
    }

    @Test
    fun `INV-AC-14105 count 0 and totals 0_00 against empty contents gives Agrees`() {
        val empty = BatchContents(0, BigDecimal("0.00"), BigDecimal("0.00"))
        BatchManifestAgreement.check(declared(0, "0.00", "0.00"), empty) shouldBe BatchManifestVerdict.Agrees
    }

    @Test
    fun `INV-AC-14105 code is ING-003 and status is 422`() {
        BatchManifestAgreement.ING_003 shouldBe "ING-003"
        BatchManifestAgreement.UNPROCESSABLE shouldBe 422
        val verdict = BatchManifestAgreement.check(declared(count = 9), contents) as BatchManifestVerdict.Refused
        verdict.code shouldBe "ING-003"
        verdict.httpStatus shouldBe 422
    }

    @Test
    fun `INV-AC-14105 Agrees is never returned when any member is unequal over the eight combinations`() {
        for (mask in 0..7) {
            val decl =
                declared(
                    count = if (mask and 1 != 0) 9 else 3,
                    net = if (mask and 2 != 0) "1.00" else "300.00",
                    tax = if (mask and 4 != 0) "1.00" else "21.00",
                )
            val verdict = BatchManifestAgreement.check(decl, contents)
            if (mask == 0) {
                verdict shouldBe BatchManifestVerdict.Agrees
            } else {
                (verdict as BatchManifestVerdict.Refused).mismatches.size shouldBe Integer.bitCount(mask)
            }
        }
    }
}
