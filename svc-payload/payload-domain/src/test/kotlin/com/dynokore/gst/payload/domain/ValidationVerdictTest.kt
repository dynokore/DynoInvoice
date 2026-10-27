package com.dynokore.gst.payload.domain

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ValidationVerdictTest {
    private val rule = "BR-111-GST-SG"
    private val esc = listOf(SeverityEscalation(rule, setOf("V")))

    private fun classify(
        id: String,
        flag: PeppolFlag,
        version: String = "V",
        escalations: List<SeverityEscalation> = esc,
    ) = LayerOneSeverity.classify(SchematronFailure(id, flag), version, escalations)

    @Test
    fun `INV-AC-522 a warning listed for the version is BLOCK with PAY-001`() {
        classify(rule, PeppolFlag.WARNING) shouldBe ClassifiedFinding(rule, Severity.BLOCK, "PAY-001")
    }

    @Test
    fun `INV-AC-1588 a warning with no escalation is WARN without a code and the verdict is Passed`() {
        val f = classify(rule, PeppolFlag.WARNING, escalations = emptyList())
        f shouldBe ClassifiedFinding(rule, Severity.WARN, null)
        ValidationVerdict.of(listOf(f), MdeResult.Complete) shouldBe ValidationVerdict.Passed(listOf(f))
    }

    @Test
    fun `INV-AC-1580 a fatal failure and a missing MDE give Blocked with both codes`() {
        val f = classify("PEPPOL-X", PeppolFlag.FATAL)
        val v = ValidationVerdict.of(listOf(f), MdeResult.Missing(listOf("IBT-022")))
        v shouldBe ValidationVerdict.Blocked(listOf(f), listOf("IBT-022"), emptyList())
        (v as ValidationVerdict.Blocked).codes() shouldBe listOf("PAY-002", "PAY-001")
    }

    @Test
    fun `INV-AC-521 layer two decides alone, an empty layer one with a missing Doc UUID is Blocked`() {
        val v = ValidationVerdict.of(emptyList(), MdeResult.Missing(listOf("DOC_UUID_ID")))
        v shouldBe ValidationVerdict.Blocked(emptyList(), listOf("DOC_UUID_ID"), emptyList())
        (v as ValidationVerdict.Blocked).codes() shouldBe listOf("PAY-001")
    }

    @Test
    fun `a FATAL rule listed in an escalation stays PAY-002`() {
        classify(rule, PeppolFlag.FATAL) shouldBe ClassifiedFinding(rule, Severity.BLOCK, "PAY-002")
    }

    @Test
    fun `an escalation for another version does not escalate`() {
        classify(rule, PeppolFlag.WARNING, version = "V2") shouldBe ClassifiedFinding(rule, Severity.WARN, null)
        classify(rule, PeppolFlag.WARNING, version = "v") shouldBe ClassifiedFinding(rule, Severity.WARN, null)
    }

    @Test
    fun `look-alike rule ids do not match`() {
        classify("br-111-gst-sg", PeppolFlag.WARNING).severity shouldBe Severity.WARN
        classify("BR-111-GST-SG ", PeppolFlag.WARNING).severity shouldBe Severity.WARN
    }

    @Test
    fun `two escalations for one rule give the same result in any order`() {
        val a = SeverityEscalation(rule, setOf("V"))
        val b = SeverityEscalation(rule, setOf("V3"))
        classify(rule, PeppolFlag.WARNING, escalations = listOf(a, b)) shouldBe
            classify(rule, PeppolFlag.WARNING, escalations = listOf(b, a))
        classify(rule, PeppolFlag.WARNING, escalations = listOf(b, a)).code shouldBe "PAY-001"
    }

    @Test
    fun `two PAY-001 findings give the code once`() {
        val blocks = listOf(classify(rule, PeppolFlag.WARNING), classify(rule, PeppolFlag.WARNING))
        val v = ValidationVerdict.of(blocks, MdeResult.Missing(listOf("IBT-022"))) as ValidationVerdict.Blocked
        v.codes() shouldBe listOf("PAY-001")
        v.blocks shouldBe blocks
    }

    @Test
    fun `Complete with only warnings is Passed and blocks keep input order`() {
        val w = ClassifiedFinding("W1", Severity.WARN, null)
        ValidationVerdict.of(listOf(w), MdeResult.Complete) shouldBe ValidationVerdict.Passed(listOf(w))
        val b1 = ClassifiedFinding("B1", Severity.BLOCK, "PAY-002")
        val b2 = ClassifiedFinding("B2", Severity.BLOCK, "PAY-001")
        ValidationVerdict.of(listOf(b2, w, b1), MdeResult.Complete) shouldBe
            ValidationVerdict.Blocked(listOf(b2, b1), emptyList(), listOf(w))
    }

    @Test
    fun `the enums have exactly the specified constants`() {
        PeppolFlag.entries.map { it.name } shouldBe listOf("FATAL", "WARNING")
        Severity.entries.map { it.name } shouldBe listOf("BLOCK", "WARN")
    }

    @Test
    fun `code constants equal the catalogue codes of error-codes yaml`() {
        LayerOneSeverity.PAY_001 shouldBe "PAY-001"
        LayerOneSeverity.PAY_002 shouldBe "PAY-002"
    }
}
