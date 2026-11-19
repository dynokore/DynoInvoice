package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.persistence.DecisionTraceContent
import com.dynokore.gst.evidence.application.lookup.StoredDecisionTrace
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant

class DecisionTraceContentTest {
    private val at = Instant.parse("2026-03-20T03:10:00Z")
    private val pack = """{"packIdentifier":"p","version":"1"}"""
    private val input = """{"name":"n","value":"v"}"""

    private fun entry(
        code: String = "RUL-001",
        rule: String = "R1",
        packs: String = "[$pack]",
        inputs: String = "[$input]",
    ) = """{"code":"$code","ruleId":"$rule","packVersions":$packs,"inputs":$inputs,"extra":1}"""

    private fun parse(json: String) = DecisionTraceContent.parse(at, json.toByteArray())

    private fun wrap(vararg entries: String) = """{"handler":"h","decisions":[${entries.joinToString(",")}]}"""

    @Test
    fun `INV-AC-20775 a valid trace with two decisions and an extra member handler parses`() {
        val valid = parse(wrap(entry(), entry(code = "RUL-002"))).shouldBeInstanceOf<StoredDecisionTrace.Valid>()
        valid.decisions.map { it.code } shouldBe listOf("RUL-001", "RUL-002")
        valid.recordedAt shouldBe at
    }

    @Test
    fun `INV-AC-20775 content that is not an object, has no decisions or empty decisions is Malformed`() {
        parse("[]") shouldBe StoredDecisionTrace.Malformed
        parse("""{"handler":"h"}""") shouldBe StoredDecisionTrace.Malformed
        parse("""{"decisions":[]}""") shouldBe StoredDecisionTrace.Malformed
        parse("not json") shouldBe StoredDecisionTrace.Malformed
    }

    @Test
    fun `INV-AC-20775 a code breaking the pattern is Malformed`() {
        parse(wrap(entry(code = "RUL-01"))) shouldBe StoredDecisionTrace.Malformed
        parse(wrap(entry(code = "rul-001"))) shouldBe StoredDecisionTrace.Malformed
    }

    @Test
    fun `INV-AC-20775 a ruleId empty or of 81 characters is Malformed, 80 is valid`() {
        parse(wrap(entry(rule = ""))) shouldBe StoredDecisionTrace.Malformed
        parse(wrap(entry(rule = "x".repeat(81)))) shouldBe StoredDecisionTrace.Malformed
        parse(wrap(entry(rule = "x".repeat(80)))).shouldBeInstanceOf<StoredDecisionTrace.Valid>()
    }

    @Test
    fun `INV-AC-20775 empty packVersions or a pack version with no version is Malformed`() {
        parse(wrap(entry(packs = "[]"))) shouldBe StoredDecisionTrace.Malformed
        parse(wrap(entry(packs = """[{"packIdentifier":"p"}]"""))) shouldBe StoredDecisionTrace.Malformed
    }

    @Test
    fun `INV-AC-20775 an input with no value or a businessTermId of IBT-12 is Malformed`() {
        parse(wrap(entry(inputs = """[{"name":"n"}]"""))) shouldBe StoredDecisionTrace.Malformed
        parse(wrap(entry(inputs = """[{"name":"n","value":"v","businessTermId":"IBT-12"}]"""))) shouldBe
            StoredDecisionTrace.Malformed
    }

    @Test
    fun `INV-AC-20775 one broken entry makes the whole trace Malformed, never a partial list`() {
        parse(wrap(entry(), entry(code = "RUL-01"))) shouldBe StoredDecisionTrace.Malformed
    }

    @Test
    fun `INV-AC-20775 a businessTermId of IBT-123 and an empty inputs list are valid`() {
        val withTerm = """[{"name":"n","value":"v","businessTermId":"IBT-123"}]"""
        val v = parse(wrap(entry(inputs = withTerm))).shouldBeInstanceOf<StoredDecisionTrace.Valid>()
        v.decisions
            .single()
            .inputs
            .single()
            .businessTermId shouldBe "IBT-123"
        parse(wrap(entry(inputs = "[]"))).shouldBeInstanceOf<StoredDecisionTrace.Valid>()
    }
}
