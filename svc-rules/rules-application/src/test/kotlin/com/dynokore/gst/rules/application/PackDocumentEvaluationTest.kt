package com.dynokore.gst.rules.application

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.rules.domain.dryrun.SampledDocument
import com.dynokore.gst.rules.domain.resolution.PackLookup
import com.dynokore.gst.rules.domain.resolution.PackTypes
import com.dynokore.platform.rulepackkernel.PackId
import com.dynokore.platform.rulepackkernel.PackManifest
import com.dynokore.platform.rulepackkernel.PackType
import com.dynokore.platform.rulepackkernel.PackVersion
import com.dynokore.platform.rulepackkernel.PackVersionLabel
import com.dynokore.platform.rulepackkernel.evaluation.EvaluationInputs
import com.dynokore.platform.rulepackkernel.evaluation.Evaluator
import com.dynokore.platform.rulepackkernel.evaluation.Rule
import com.dynokore.platform.rulepackkernel.evaluation.RuleResult
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** The production evaluation port over the real EvaluateDocument, with in-memory versions and rules. */
class PackDocumentEvaluationTest {
    private val tenant = TenantId(UUID.fromString("11111111-1111-4111-8111-111111111111"))
    private val type = PackTypes.MAPPING
    private val asked = mutableListOf<String>()

    private class FixedRule(
        override val ruleId: String,
        private val verdict: String,
    ) : Rule {
        override val packType: PackType = PackTypes.MAPPING

        override fun evaluate(inputs: EvaluationInputs) = RuleResult(verdict)
    }

    private fun version(
        label: String,
        from: LocalDate,
    ) = PackVersion(PackManifest(PackId("mapping-pack"), type, PackVersionLabel(label), from, null, "1.0.0"))

    private val versions =
        mapOf("v1" to version("v1", LocalDate.of(2026, 1, 1)), "v2" to version("v2", LocalDate.of(2026, 10, 1)))

    private fun port(vararg rules: Rule) =
        PackDocumentEvaluation(
            { _, _, label -> versions[label].also { asked += label } },
            PackLookup { _, _, _ -> error("the pack type of the call must not resolve by date") },
            Evaluator(rules.toList()),
            listOf(type),
        )

    private val document =
        SampledDocument(
            1,
            1,
            "00000000-0000-4000-8000-000000000001",
            "P",
            "E",
            LocalDate.of(2026, 11, 1),
            "C",
            "BILL_FOR_PAYMENT",
            null,
            null,
            false,
            BigDecimal("1.00"),
            BigDecimal("0.09"),
        )

    @Test
    fun `the named version is used for the call and an unknown version gives another outcome`() {
        val p = port(FixedRule("M-1", "MAPPED"))
        p.evaluate(tenant, type.value, "v1", document).code shouldBe "MAPPED"
        p.evaluate(tenant, type.value, "v9", document).let {
            (it.code to it.severity) shouldBe
                ("RUL-002" to ErrorCode.RUL_002.severity.name)
        }
        asked shouldBe listOf("v1", "v9")
    }

    @Test
    fun `effectiveFrom is read from the manifest of the named version`() {
        val p = port(FixedRule("M-1", "MAPPED"))
        p.effectiveFrom(tenant, type.value, "v2") shouldBe LocalDate.of(2026, 10, 1)
        p.effectiveFrom(tenant, type.value, "v1") shouldBe LocalDate.of(2026, 1, 1)
        shouldThrow<IllegalStateException> { p.effectiveFrom(tenant, type.value, "v9") }
    }

    @Test
    fun `no rule fired and conflicting rules fail closed`() {
        shouldThrow<IllegalStateException> { port().evaluate(tenant, type.value, "v1", document) }
        shouldThrow<IllegalStateException> {
            port(FixedRule("A", "X"), FixedRule("B", "Y")).evaluate(tenant, type.value, "v1", document)
        }
    }

    @Test
    fun `an unknown pack type name is refused`() {
        shouldThrow<IllegalStateException> { port(FixedRule("M-1", "MAPPED")).evaluate(tenant, "NOPE", "v1", document) }
    }
}
