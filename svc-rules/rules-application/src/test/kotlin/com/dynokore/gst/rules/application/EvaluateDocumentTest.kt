package com.dynokore.gst.rules.application

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.rules.domain.coding.CatalogueEntry
import com.dynokore.gst.rules.domain.coding.CodeCatalogue
import com.dynokore.gst.rules.domain.coding.Direction
import com.dynokore.gst.rules.domain.coding.LineInput
import com.dynokore.gst.rules.domain.coding.MappingEntry
import com.dynokore.gst.rules.domain.coding.ScenarioData
import com.dynokore.gst.rules.domain.coding.SelfBillingAssignment
import com.dynokore.gst.rules.domain.coding.SubmissionType
import com.dynokore.gst.rules.domain.resolution.PackResolver
import com.dynokore.gst.rules.domain.resolution.PackTypes
import com.dynokore.gst.rules.domain.resolution.ResolutionDates
import com.dynokore.gst.rules.domain.resolution.TransactionDate
import com.dynokore.platform.rulepackkernel.InMemoryPackRegistry
import com.dynokore.platform.rulepackkernel.PackId
import com.dynokore.platform.rulepackkernel.PackManifest
import com.dynokore.platform.rulepackkernel.PackType
import com.dynokore.platform.rulepackkernel.PackVersion
import com.dynokore.platform.rulepackkernel.PackVersionLabel
import com.dynokore.platform.rulepackkernel.evaluation.AppliedPack
import com.dynokore.platform.rulepackkernel.evaluation.EvaluationInputs
import com.dynokore.platform.rulepackkernel.evaluation.EvaluationOutcome
import com.dynokore.platform.rulepackkernel.evaluation.Evaluator
import com.dynokore.platform.rulepackkernel.evaluation.Rule
import com.dynokore.platform.rulepackkernel.evaluation.RuleResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/** The evaluation core at the use-case boundary, over the kernel's registry. */
class EvaluateDocumentTest {
    private object MappingRule : Rule {
        override val ruleId = "M-1"
        override val packType: PackType = PackTypes.MAPPING

        override fun evaluate(inputs: EvaluationInputs) = RuleResult("MAPPED", terminal = true)
    }

    private fun mapping(
        label: String,
        from: LocalDate,
        to: LocalDate? = null,
    ) = PackVersion(
        PackManifest(PackId("mapping-pack"), PackTypes.MAPPING, PackVersionLabel(label), from, to, "1.0.0"),
    )

    private fun useCase(vararg versions: PackVersion) =
        EvaluateDocument(
            PackResolver(InMemoryPackRegistry().also { r -> versions.forEach(r::register) }),
            Evaluator(listOf(MappingRule)),
            listOf(PackTypes.MAPPING),
        )

    private fun command(date: LocalDate) =
        EvaluateCommand(
            null,
            ResolutionDates(TransactionDate(date)),
            EvaluationInputs.of(mapOf("internalTaxCode" to "SR-STD")),
        )

    @Test
    fun `INV-AC-1723 a document dated 20 June evaluated on 15 July is bound to v1 and v1 is returned to record`() {
        val uc =
            useCase(
                mapping("v1", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 1)),
                mapping("v2", LocalDate.of(2026, 7, 1)),
            )
        val result = uc.handle(command(LocalDate.of(2026, 6, 20)))
        val decided = result.shouldBeInstanceOf<EvaluateResult.Decided>().evaluation
        decided.packVersionsApplied
            .single()
            .version.value shouldBe "v1"
    }

    @Test
    fun `INV-AC-1781 a document dated 20 June before the first version of 1 July is refused with RUL-002`() {
        val refused = useCase(mapping("v1", LocalDate.of(2026, 7, 1))).handle(command(LocalDate.of(2026, 6, 20)))
        refused.shouldBeInstanceOf<EvaluateResult.Refused>().code shouldBe ErrorCode.RUL_002
    }

    @Test
    fun `INV-AC-2105 a backfilled November document is decided by the November version and the trace names it`() {
        val uc =
            useCase(
                mapping("2025.11", LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 1)),
                mapping("2026.1", LocalDate.of(2026, 1, 1)),
            )
        val result = uc.handle(command(LocalDate.of(2025, 11, 14)))
        val decided = result.shouldBeInstanceOf<EvaluateResult.Decided>().evaluation
        decided.trace.steps
            .single()
            .packVersion.value shouldBe "2025.11"
    }

    @Test
    fun `INV-AC-1728 the same command evaluated twice gives the identical result`() {
        val uc = useCase(mapping("v1", LocalDate.of(2026, 1, 1)))
        uc.handle(command(LocalDate.of(2026, 3, 1))) shouldBe uc.handle(command(LocalDate.of(2026, 3, 1)))
    }

    @Test
    fun `code delegates to the wired DocumentCoder at the explicit date`() {
        val seen = mutableListOf<LocalDate>()
        val entry = CatalogueEntry("SR", "TEST", "SUPPLY", "9", null, null, null)
        val self = SelfBillingAssignment(Direction.SALE, SubmissionType.TYPE_1A)
        val coder =
            DocumentCoder(
                { _, d -> seen.add(d.value).let { listOf(MappingEntry("STD", "SR")) } },
                { _, _ -> CodeCatalogue(true, listOf(entry)) },
                { _, _ -> ScenarioData(self, self, emptyList(), emptyList()) },
            )
        val uc = EvaluateDocument(PackResolver(InMemoryPackRegistry()), Evaluator(emptyList()), emptyList(), coder)
        val date = LocalDate.of(2026, 3, 1)
        val result =
            uc.code(
                CodeDocumentCommand(
                    null,
                    TransactionDate(date),
                    listOf(DocumentLine("1", BigDecimal("100.00"), BigDecimal("9.00"), LineInput("STD"))),
                ),
            )
        result
            .shouldBeInstanceOf<DocumentCodingResult.Coded>()
            .document.subtotals
            .single()
            .tax shouldBe BigDecimal("9.00")
        seen shouldBe listOf(date)
    }

    private class FixedRule(
        override val ruleId: String,
        private val verdict: String,
    ) : Rule {
        override val packType: PackType = PackTypes.SCOPE

        override fun evaluate(inputs: EvaluationInputs) = RuleResult(verdict)
    }

    private fun scopeUseCase(
        first: String,
        second: String,
    ): Pair<EvaluateDocument, Evaluator> {
        val registry = InMemoryPackRegistry()
        registry.register(
            PackVersion(
                PackManifest(
                    PackId("scope-pack"),
                    PackTypes.SCOPE,
                    PackVersionLabel("s1"),
                    LocalDate.of(2026, 1, 1),
                    null,
                    "1.0.0",
                ),
            ),
        )
        val evaluator = Evaluator(listOf(FixedRule("S-1", first), FixedRule("S-2", second)))
        return EvaluateDocument(PackResolver(registry), evaluator, listOf(PackTypes.SCOPE)) to evaluator
    }

    @Test
    fun `INV-AC-19148 two rules firing with different verdicts give Conflict with the library rule ids, not Decided`() {
        val (uc, evaluator) = scopeUseCase("IN_SCOPE", "OUT_OF_SCOPE")
        val result = uc.handle(command(LocalDate.of(2026, 3, 1)))
        val applied = listOf(AppliedPack(PackTypes.SCOPE, PackId("scope-pack"), PackVersionLabel("s1")))
        val library =
            evaluator
                .evaluate(applied, command(LocalDate.of(2026, 3, 1)).inputs)
                .shouldBeInstanceOf<EvaluationOutcome.Conflict>()
        result.shouldBeInstanceOf<EvaluateResult.Conflict>().ruleIds shouldBe library.ruleIds
        result.ruleIds shouldBe listOf("S-1", "S-2")
    }

    @Test
    fun `INV-AC-19148 two rules that fire with the same verdict give Decided with that verdict`() {
        val (uc, _) = scopeUseCase("IN_SCOPE", "IN_SCOPE")
        uc
            .handle(command(LocalDate.of(2026, 3, 1)))
            .shouldBeInstanceOf<EvaluateResult.Decided>()
            .evaluation.verdict shouldBe "IN_SCOPE"
    }
}
