package com.dynokore.gst.payload.domain

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class UnadjustedCreditNoteCheckTest {
    private fun lines(vararg codes: String) = codes.mapIndexed { i, c -> LineCode(i + 1, c) }

    @Test
    fun `INV-AC-764 the SR constant equals SR`() {
        IRAS_STANDARD_RATED_CODE shouldBe "SR"
    }

    @Test
    fun `INV-AC-764 marked with all SR passes`() {
        UnadjustedCreditNoteCheck.check(true, lines("SR", "SR")) shouldBe CheckResult.Passes
    }

    @Test
    fun `INV-AC-10440 marked with one ZR among three returns exactly that line`() {
        UnadjustedCreditNoteCheck.check(true, lines("SR", "ZR", "SR")) shouldBe
            CheckResult.Violations(listOf(LineCode(2, "ZR")))
    }

    @Test
    fun `INV-AC-764 look-alike codes each return a violation`() {
        for (code in listOf("sr", "SRCA-S", "SRRC", "SRLVG", "SR ", "S R")) {
            UnadjustedCreditNoteCheck.check(true, lines("SR", code)) shouldBe
                CheckResult.Violations(listOf(LineCode(2, code)))
        }
    }

    @Test
    fun `INV-AC-10440 violations keep line order`() {
        UnadjustedCreditNoteCheck.check(true, lines("ZR", "SR", "ES")) shouldBe
            CheckResult.Violations(listOf(LineCode(1, "ZR"), LineCode(3, "ES")))
    }

    @Test
    fun `INV-AC-10440 not marked with ZR passes`() {
        UnadjustedCreditNoteCheck.check(false, lines("SR", "ZR")) shouldBe CheckResult.Passes
    }

    @Test
    fun `INV-AC-764 empty lines throw`() {
        assertThrows<IllegalArgumentException> { UnadjustedCreditNoteCheck.check(true, emptyList()) }
    }
}
