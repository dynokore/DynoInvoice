package com.dynokore.gst.payload.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MdeCompletenessTest {
    private val docUuidId = "BT-SG-" + "003"
    private val both = setOf("380", "381")

    private fun row(
        id: String,
        status: IrasStatus = IrasStatus.MANDATORY,
        codes: Set<String> = both,
        types: Set<SubmissionType> = setOf(SubmissionType.TYPE_1A),
        scenarios: Set<String> = emptySet(),
    ) = MdeRow(id, codes, types, status, scenarios)

    private fun check(
        rows: List<MdeRow>,
        values: Map<String, String?>,
        code: String = "380",
        type: SubmissionType = SubmissionType.TYPE_1A,
        scenarios: Set<String> = emptySet(),
    ) = MdeCompleteness.check(rows, code, type, scenarios, values)

    @Test
    fun `INV-AC-521 a document with every mandatory term but the Doc UUID gives Missing of the Doc UUID`() {
        val rows = listOf(row(docUuidId), row("IBT-030"), row("IBT-031"))
        check(rows, mapOf("IBT-030" to "a", "IBT-031" to "b")) shouldBe MdeResult.Missing(listOf(docUuidId))
    }

    @Test
    fun `INV-AC-1502 a mandatory row is required whatever PINT-SG says, the function has no cardinality input`() {
        val rows = listOf(row("IBT-152"))
        check(rows, mapOf("IBT-030" to "a")) shouldBe MdeResult.Missing(listOf("IBT-152"))
        MdeRow::class.java.declaredFields
            .map { it.name }
            .toSet() shouldBe
            setOf("businessTermId", "documentTypeCodes", "submissionTypes", "irasStatus", "requiredInScenarios")
        MdeCompleteness::class.java.methods
            .single { it.name == "check" }
            .parameters
            .map { it.type.simpleName } shouldBe listOf("List", "String", "SubmissionType", "Set", "Map")
    }

    @Test
    fun `INV-AC-1499 380 requires the invoice rows, 381 also the credit note row, other codes throw`() {
        val rows = listOf(row("IBT-030"), row("IBT-022", codes = setOf("381")))
        val values = mapOf("IBT-030" to "a")
        check(rows, values, code = "380") shouldBe MdeResult.Complete
        check(rows, values, code = "381") shouldBe MdeResult.Missing(listOf("IBT-022"))
        shouldThrow<IllegalArgumentException> { check(rows, values, code = "386") }
        shouldThrow<IllegalArgumentException> { check(rows, values, code = "") }
        shouldThrow<IllegalArgumentException> { check(rows, values, code = "3800") }
    }

    @Test
    fun `INV-AC-10245 blank and absent terms are missing, a zero amount is populated, NOT_MDE is never listed`() {
        val rows =
            listOf(row("IBT-030"), row("IBT-031"), row("IBT-047"), row("IBT-999", status = IrasStatus.NOT_MDE))
        check(rows, mapOf("IBT-030" to "   ", "IBT-047" to "0.00")) shouldBe
            MdeResult.Missing(listOf("IBT-030", "IBT-031"))
    }

    @Test
    fun `INV-AC-10246 two type codes select different sets`() {
        val rows = listOf(row("IBT-022", codes = setOf("381")))
        check(rows, emptyMap(), code = "380") shouldBe MdeResult.Complete
        check(rows, emptyMap(), code = "381") shouldBe MdeResult.Missing(listOf("IBT-022"))
        shouldThrow<IllegalArgumentException> { check(rows, emptyMap(), code = "3800") }
    }

    @Test
    fun `INV-AC-10247 a scenario term is required exactly in its listed scenario`() {
        val rows = listOf(row("IBT-022", status = IrasStatus.MANDATORY_IN_SCENARIOS, scenarios = setOf("SCHEME_X")))
        check(rows, emptyMap(), scenarios = setOf("SCHEME_X")) shouldBe MdeResult.Missing(listOf("IBT-022"))
        check(rows, emptyMap(), scenarios = setOf("scheme_x")) shouldBe MdeResult.Complete
        check(rows, emptyMap(), scenarios = emptySet()) shouldBe MdeResult.Complete
    }

    @Test
    fun `populated means not null and not blank`() {
        val rows = listOf(row("A"))
        check(rows, mapOf("A" to "0.00")) shouldBe MdeResult.Complete
        check(rows, mapOf("A" to "")) shouldBe MdeResult.Missing(listOf("A"))
        check(rows, mapOf("A" to "   ")) shouldBe MdeResult.Missing(listOf("A"))
        check(rows, mapOf("A" to null)) shouldBe MdeResult.Missing(listOf("A"))
        check(rows, emptyMap()) shouldBe MdeResult.Missing(listOf("A"))
    }

    @Test
    fun `two rows for one id give one entry`() {
        check(
            listOf(row("A"), row("A", types = setOf(SubmissionType.TYPE_1A, SubmissionType.TYPE_2))),
            emptyMap(),
        ) shouldBe
            MdeResult.Missing(listOf("A"))
    }

    @Test
    fun `ids are ascending by string order`() {
        val rows = listOf(row("IBT-152"), row("IBT-030"), row("IBT-022"))
        check(rows, emptyMap()) shouldBe MdeResult.Missing(listOf("IBT-022", "IBT-030", "IBT-152"))
    }

    @Test
    fun `a NOT_MDE row is never required`() {
        check(
            listOf(row("A", status = IrasStatus.NOT_MDE, scenarios = setOf("S"))),
            emptyMap(),
            scenarios = setOf("S"),
        ) shouldBe
            MdeResult.Complete
    }

    @Test
    fun `a row whose submission types lack the given type does not apply`() {
        val rows = listOf(row("A", types = setOf(SubmissionType.TYPE_1B)))
        check(rows, emptyMap(), type = SubmissionType.TYPE_2) shouldBe MdeResult.Complete
        check(rows, emptyMap(), type = SubmissionType.TYPE_1B) shouldBe MdeResult.Missing(listOf("A"))
    }

    @Test
    fun `a row for 381 does not apply to 380`() {
        check(listOf(row("A", codes = setOf("381"))), emptyMap(), code = "380") shouldBe MdeResult.Complete
    }

    @Test
    fun `a row bound to another specification version is still required, there is no version input`() {
        check(listOf(row("A")), emptyMap()) shouldBe MdeResult.Missing(listOf("A"))
    }

    @Test
    fun `the enums have exactly the contract constants`() {
        IrasStatus.entries.map { it.name } shouldBe listOf("MANDATORY", "MANDATORY_IN_SCENARIOS", "NOT_MDE")
        SubmissionType.entries.map { it.name } shouldBe listOf("TYPE_1A", "TYPE_1B", "TYPE_2", "TYPE_3")
    }
}
