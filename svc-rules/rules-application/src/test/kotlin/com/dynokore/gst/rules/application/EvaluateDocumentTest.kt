package com.dynokore.gst.rules.application

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.rules.domain.evaluation.EvaluationInputs
import com.dynokore.gst.rules.domain.evaluation.Evaluator
import com.dynokore.gst.rules.domain.evaluation.Rule
import com.dynokore.gst.rules.domain.evaluation.RuleResult
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
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
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
        EvaluateCommand(null, ResolutionDates(TransactionDate(date)), EvaluationInputs.of(mapOf("internalTaxCode" to "SR-STD")))

    @Test
    fun `INV-AC-1723 a document dated 20 June evaluated on 15 July is bound to v1 and v1 is returned to record`() {
        val uc = useCase(mapping("v1", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 1)), mapping("v2", LocalDate.of(2026, 7, 1)))
        val decided = uc.handle(command(LocalDate.of(2026, 6, 20))).shouldBeInstanceOf<EvaluateResult.Decided>().evaluation
        decided.packVersionsApplied.single().version.value shouldBe "v1"
    }

    @Test
    fun `INV-AC-1781 a document dated 20 June before the first version of 1 July is refused with RUL-002`() {
        val refused = useCase(mapping("v1", LocalDate.of(2026, 7, 1))).handle(command(LocalDate.of(2026, 6, 20)))
        refused.shouldBeInstanceOf<EvaluateResult.Refused>().code shouldBe ErrorCode.RUL_002
    }

    @Test
    fun `INV-AC-2105 a backfilled document of last November is decided by the November version and its trace names it`() {
        val uc = useCase(mapping("2025.11", LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 1)), mapping("2026.1", LocalDate.of(2026, 1, 1)))
        val decided = uc.handle(command(LocalDate.of(2025, 11, 14))).shouldBeInstanceOf<EvaluateResult.Decided>().evaluation
        decided.trace.steps.single().packVersion.value shouldBe "2025.11"
    }

    @Test
    fun `INV-AC-1728 the same command evaluated twice gives the identical result`() {
        val uc = useCase(mapping("v1", LocalDate.of(2026, 1, 1)))
        uc.handle(command(LocalDate.of(2026, 3, 1))) shouldBe uc.handle(command(LocalDate.of(2026, 3, 1)))
    }
}
