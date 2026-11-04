package com.dynokore.gst.payload.domain

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class PreIssuanceVerdictDecisionTest {
    private val pay011 = PreIssuanceReason("PAY-011", "supplierGstn", true)

    private fun nr(
        type: PreIssuanceSubmissionType,
        amount: String?,
    ) = PreIssuanceVerdictDecision.decide(PreIssuanceInput(type, PreIssuanceRegisterStatus.NOT_REGISTERED, amount))

    private fun decided(
        verdict: PreIssuanceVerdict,
        reasons: List<PreIssuanceReason> = emptyList(),
        flags: Set<PreIssuanceFlag> = emptySet(),
    ) = PreIssuanceOutcome.Decided(verdict, reasons, flags)

    @Test
    fun `INV-AC-13552 D1 90_00 on Type 1A is BLOCK with one PAY-011 reason`() {
        nr(PreIssuanceSubmissionType.TYPE_1A, "90.00") shouldBe decided(PreIssuanceVerdict.BLOCK, listOf(pay011))
    }

    @Test
    fun `INV-AC-13552 D2 0_00 is PASS with no reasons`() {
        nr(PreIssuanceSubmissionType.TYPE_1A, "0.00") shouldBe decided(PreIssuanceVerdict.PASS)
    }

    @Test
    fun `INV-AC-13552 D3 negative amount is BLOCK`() {
        nr(PreIssuanceSubmissionType.TYPE_1A, "-90.00") shouldBe decided(PreIssuanceVerdict.BLOCK, listOf(pay011))
    }

    @Test
    fun `INV-AC-13552 D4 line amounts are not read, document zero is PASS`() {
        // The input carries no line amounts; the draft lines +90.00 and -90.00 sum to zero and are ignored.
        val lines = listOf(BigDecimal("90.00"), BigDecimal("-90.00"))
        lines.fold(BigDecimal.ZERO, BigDecimal::add).compareTo(BigDecimal.ZERO) shouldBe 0
        nr(PreIssuanceSubmissionType.TYPE_1A, "0.00") shouldBe decided(PreIssuanceVerdict.PASS)
    }

    @Test
    fun `INV-AC-13552 zero look-alikes are PASS`() {
        for (a in listOf("0", "0.0", "-0.00", "-0", "000", "-0.0")) {
            nr(PreIssuanceSubmissionType.TYPE_1A, a) shouldBe decided(PreIssuanceVerdict.PASS)
        }
    }

    @Test
    fun `INV-AC-13552 Types 2, 1B and 3 are WARN with PAY-011 overridable`() {
        for (t in PreIssuanceSubmissionType.entries.filter { it != PreIssuanceSubmissionType.TYPE_1A }) {
            nr(t, "90.00") shouldBe decided(PreIssuanceVerdict.WARN, listOf(pay011))
            nr(t, "-0.01") shouldBe decided(PreIssuanceVerdict.WARN, listOf(pay011))
        }
    }

    @Test
    fun `INV-AC-13552 REGISTERED is PASS for every type and amount`() {
        for (t in PreIssuanceSubmissionType.entries) {
            for (a in listOf("90.00", "0.00", "-90.00")) {
                PreIssuanceVerdictDecision.decide(PreIssuanceInput(t, PreIssuanceRegisterStatus.REGISTERED, a)) shouldBe
                    decided(PreIssuanceVerdict.PASS)
            }
        }
    }

    @Test
    fun `INV-AC-13552 REGISTER_UNAVAILABLE is WARN with flag and never BLOCK for every type`() {
        for (t in PreIssuanceSubmissionType.entries) {
            for (a in listOf("90.00", "0.00", "-90.00")) {
                PreIssuanceVerdictDecision.decide(
                    PreIssuanceInput(t, PreIssuanceRegisterStatus.REGISTER_UNAVAILABLE, a),
                ) shouldBe decided(PreIssuanceVerdict.WARN, flags = setOf(PreIssuanceFlag.REGISTER_UNAVAILABLE))
            }
        }
    }

    @Test
    fun `INV-AC-13552 missing amount is refused, never PASS`() {
        for (s in PreIssuanceRegisterStatus.entries) {
            PreIssuanceVerdictDecision.decide(
                PreIssuanceInput(PreIssuanceSubmissionType.TYPE_1A, s, null),
            ) shouldBe PreIssuanceOutcome.Refused(PreIssuanceInputError.MISSING_AMOUNT)
        }
    }

    @Test
    fun `INV-AC-13552 malformed amounts are refused`() {
        val bad =
            listOf(
                "9O.00",
                "1e2",
                "",
                " ",
                "90.00 ",
                "90.000",
                ".5",
                "5.",
                "+5",
                "--5",
                "1,000.00",
                "90.00\n",
                "٩٠.٠٠",
                "NaN",
                "12345678901234567890",
            )
        for (a in bad) {
            nr(PreIssuanceSubmissionType.TYPE_1A, a) shouldBe
                PreIssuanceOutcome.Refused(PreIssuanceInputError.INVALID_AMOUNT)
        }
    }

    @Test
    fun `INV-AC-13552 amount of 17 integer digits is accepted`() {
        nr(PreIssuanceSubmissionType.TYPE_1A, "12345678901234567.99") shouldBe
            decided(PreIssuanceVerdict.BLOCK, listOf(pay011))
    }

    @Test
    fun `INV-AC-13552 invalid status or type strings are refused`() {
        for (s in listOf("not_registered", "UNKNOWN", "", "NOT_REGISTERED ")) {
            PreIssuanceVerdictDecision.decideRaw("TYPE_1A", s, "90.00") shouldBe
                PreIssuanceOutcome.Refused(PreIssuanceInputError.INVALID_STATUS)
        }
        PreIssuanceVerdictDecision.decideRaw("TYPE_1A", null, "90.00") shouldBe
            PreIssuanceOutcome.Refused(PreIssuanceInputError.INVALID_STATUS)
        for (t in listOf("1A", "TYPE_1a", "type_1a", "")) {
            PreIssuanceVerdictDecision.decideRaw(t, "NOT_REGISTERED", "90.00") shouldBe
                PreIssuanceOutcome.Refused(PreIssuanceInputError.INVALID_SUBMISSION_TYPE)
        }
        PreIssuanceVerdictDecision
            .decideRaw(null, "NOT_REGISTERED", "90.00")
            .shouldBeInstanceOf<PreIssuanceOutcome.Refused>()
    }

    @Test
    fun `INV-AC-13552 valid strings decide like typed input`() {
        PreIssuanceVerdictDecision.decideRaw("TYPE_1A", "NOT_REGISTERED", "90.00") shouldBe
            decided(PreIssuanceVerdict.BLOCK, listOf(pay011))
        PreIssuanceVerdictDecision.decideRaw("TYPE_2", "NOT_REGISTERED", null) shouldBe
            PreIssuanceOutcome.Refused(PreIssuanceInputError.MISSING_AMOUNT)
    }

    @Test
    fun `INV-AC-13552 reason code look-alikes fail equality`() {
        val code = (nr(PreIssuanceSubmissionType.TYPE_1A, "90.00") as PreIssuanceOutcome.Decided).reasons.single().code
        code shouldBe "PAY-011"
        for (other in listOf("PAY-010", "PAY-0011", "pay-011", "PAY-011 ")) {
            (code == other) shouldBe false
        }
    }

    @Test
    fun `INV-AC-13552 same input gives same output`() {
        nr(PreIssuanceSubmissionType.TYPE_2, "5.00") shouldBe nr(PreIssuanceSubmissionType.TYPE_2, "5.00")
    }
}
