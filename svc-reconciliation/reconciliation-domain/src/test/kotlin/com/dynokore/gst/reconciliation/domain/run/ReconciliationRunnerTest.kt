package com.dynokore.gst.reconciliation.domain.run

import com.dynokore.gst.reconciliation.domain.boxes.Basis
import com.dynokore.gst.reconciliation.domain.boxes.BoxFeed
import com.dynokore.gst.reconciliation.domain.boxes.Direction
import com.dynokore.gst.reconciliation.domain.boxes.MappingEntry
import com.dynokore.gst.reconciliation.domain.boxes.UnmappedLine
import com.dynokore.gst.reconciliation.domain.sgd.NotReconcilableReason
import com.dynokore.gst.reconciliation.domain.sgd.SgdAllocation
import com.dynokore.gst.reconciliation.domain.sgd.SgdLine
import com.dynokore.gst.reconciliation.domain.variance.DocumentFacts
import com.dynokore.gst.reconciliation.domain.variance.VarianceClass
import com.dynokore.gst.reconciliation.domain.variance.VarianceClasses
import com.dynokore.platform.reconciliationengine.MissingSide
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ReconciliationRunnerTest {
    private fun d(s: String) = BigDecimal(s)

    private val entries =
        listOf(
            MappingEntry(
                "SR",
                Direction.SALE,
                listOf(BoxFeed("1", Basis.TAXABLE_AMOUNT), BoxFeed("6", Basis.TAX_AMOUNT)),
            ),
            MappingEntry("ES", Direction.SALE, listOf(BoxFeed("3", Basis.TAXABLE_AMOUNT))),
            MappingEntry("BAD", Direction.SALE, listOf(BoxFeed("1 ", Basis.TAXABLE_AMOUNT))),
        )

    private fun facts(verdict: String? = null) = DocumentFacts(verdict, null)

    private fun lines(
        code: String,
        amount: String,
        tax: String = "0.00",
        number: Int = 1,
    ) = SgdAllocation.Allocated(listOf(SgdLine(number, code, d(amount), d(tax))))

    private fun version(
        number: Int,
        acknowledged: Boolean,
        allocation: SgdAllocation,
        verdict: String? = null,
    ) = RunDocumentVersion(number, acknowledged, facts(verdict), Direction.SALE, allocation)

    private fun doc(
        uuid: String,
        vararg versions: RunDocumentVersion,
    ) = RunDocument(uuid, versions.toList())

    private fun completed(
        figures: Map<String, String>,
        vararg documents: RunDocument,
    ): RunOutcome.Completed =
        ReconciliationRunner
            .run(ReturnFigures(3, figures.mapValues { d(it.value) }), entries, documents.toList())
            .shouldBeInstanceOf<RunOutcome.Completed>()

    @Test
    fun `INV-AC-669 no return gives PER-008 and no comparison even when documents exist`() {
        val outcome =
            ReconciliationRunner.run(null, entries, listOf(doc("A", version(1, true, lines("SR", "100.00")))))
        outcome shouldBe RunOutcome.NoReturn("PER-008")
        (outcome is RunOutcome.Completed) shouldBe false
    }

    @Test
    fun `INV-AC-677 an unsubmitted excluded transaction explains the Box 3 variance`() {
        val outcome =
            completed(
                mapOf("3" to "1000.00"),
                doc("X", version(1, false, lines("ES", "1000.00"), "EXCLUDED_TXN")),
            )
        val line = outcome.result.lines.single()
        line.comparison.missingSide shouldBe MissingSide.SUBMITTED
        line.classified
            .single()
            .category.code shouldBe "EXCLUDED_TXN"
        line.classified.single().amount shouldBe d("1000.00")
        line.classified.single().linkedRecords shouldBe listOf("X")
        line.unexplainedResidual.compareTo(BigDecimal.ZERO) shouldBe 0
        outcome.raisesPer003 shouldBe false
    }

    @Test
    fun `INV-AC-1807 a never submitted SR document stays in the unexplained residual and raises PER-003`() {
        val outcome =
            completed(
                mapOf("1" to "100.00"),
                doc("S", version(1, false, lines("SR", "100.00"), "SUBMITTABLE")),
            )
        outcome.result.unexplained
            .single()
            .residual shouldBe d("100.00")
        outcome.result.unexplained
            .single()
            .key shouldBe "1"
        outcome.raisesPer003 shouldBe true
        RunOutcome.PER_003 shouldBe "PER-003"
        val run = ReconciliationRunner::class.java.methods.single { it.name == "run" }
        run.parameterCount shouldBe 3
        run.parameterTypes.toList() shouldBe
            listOf(ReturnFigures::class.java, List::class.java, List::class.java)
    }

    @Test
    fun `INV-AC-1807 mixed a Box explained 1000 by a class and 500 by a record without class leaves 500`() {
        val outcome =
            completed(
                mapOf("3" to "1500.00"),
                doc("X", version(1, false, lines("ES", "1000.00"), "EXCLUDED_TXN")),
                doc("Y", version(1, false, lines("ES", "500.00"))),
            )
        val line = outcome.result.lines.single()
        line.classified.single().amount shouldBe d("1000.00")
        line.unexplainedResidual shouldBe d("500.00")
        outcome.raisesPer003 shouldBe true
    }

    @Test
    fun `INV-AC-1807 the residual of one Box is not netted against the explained amount of another`() {
        val outcome =
            completed(
                mapOf("1" to "500.00", "3" to "500.00"),
                doc("U", version(1, false, lines("SR", "500.00"))),
                doc("X", version(1, false, lines("ES", "1000.00"), "EXCLUDED_TXN")),
            )
        outcome.result.lines
            .associate { it.comparison.key to it.unexplainedResidual }["1"] shouldBe d("500.00")
        outcome.result.lines
            .associate { it.comparison.key to it.unexplainedResidual }["3"] shouldBe d("-500.00")
        outcome.raisesPer003 shouldBe true
    }

    @Test
    fun `INV-AC-1807 a credit note gives negative evidence that explains a negative variance`() {
        val outcome =
            completed(
                mapOf("3" to "-200.00"),
                doc("C", version(1, false, lines("ES", "-200.00"), "EXCLUDED_TXN")),
            )
        outcome.result.classifiedVariances
            .single()
            .amount shouldBe d("-200.00")
        outcome.raisesPer003 shouldBe false
    }

    @Test
    fun `INV-AC-1807 the submitted side uses the highest acknowledged version only`() {
        val outcome =
            completed(
                mapOf("1" to "100.00"),
                doc("A", version(1, true, lines("SR", "100.00")), version(2, false, lines("SR", "999.00"))),
            )
        outcome.result.comparison
            .single { it.key == "1" }
            .submittedAmount shouldBe d("100.00")
        outcome.raisesPer003 shouldBe false
    }

    private val twoLines =
        listOf(SgdLine(2, "SR", d("5.00"), d("0.00")), SgdLine(1, "XX", d("7.00"), d("0.00")))

    @Test
    fun `INV-AC-1807 sides missing are flagged and an unmapped line is listed and in no Box`() {
        val outcome =
            completed(
                mapOf("3" to "10.00"),
                doc("A", version(1, true, SgdAllocation.Allocated(twoLines))),
            )
        val sides = outcome.result.comparison.associate { it.key to it.missingSide }
        sides["3"] shouldBe MissingSide.SUBMITTED
        sides["1"] shouldBe MissingSide.RETURN
        outcome.unmappedLines shouldBe listOf(UnmappedLine("A", 1, 1, "XX"))
    }

    @Test
    fun `INV-AC-1807 a not reconcilable version is listed and contributes nothing`() {
        val outcome =
            completed(
                mapOf("1" to "0.00"),
                doc("N", version(1, true, SgdAllocation.NotReconcilable(NotReconcilableReason.NO_LINES))),
                doc(
                    "M",
                    version(1, false, SgdAllocation.NotReconcilable(NotReconcilableReason.MISSING_SGD_TOTAL)),
                ),
            )
        outcome.notReconcilable.map { Triple(it.docUuid, it.version, it.reason) } shouldBe
            listOf(
                Triple("M", 1, NotReconcilableReason.MISSING_SGD_TOTAL),
                Triple("N", 1, NotReconcilableReason.NO_LINES),
            )
        outcome.result.classifiedVariances shouldBe emptyList()
        outcome.raisesPer003 shouldBe false
    }

    @Test
    fun `INV-AC-1807 a Box key with a space is a different key from the same key without it`() {
        val outcome =
            completed(
                mapOf("1" to "0.00"),
                doc("A", version(1, true, lines("BAD", "5.00"))),
            )
        outcome.result.comparison.map { it.key } shouldBe listOf("1", "1 ")
    }

    @Test
    fun `INV-AC-1807 a blank Box key propagates the engine refusal`() {
        shouldThrow<IllegalArgumentException> {
            ReconciliationRunner.run(ReturnFigures(1, mapOf(" " to d("1.00"))), entries, emptyList())
        }
    }

    @Test
    fun `INV-AC-1807 the eight category codes are the VarianceClass members other than UNEXPLAINED`() {
        val verdicts =
            mapOf(
                "EXCLUDED_TXN" to ("EXCLUDED_TXN" to null),
                "PRE_OBLIGATION" to ("PRE_OBLIGATION" to null),
                "OUTSIDE_BY_RULE" to ("OUT_OF_SCOPE" to null),
                "BUYER_REJECTED" to ("NOT_SUBMITTABLE" to "BUYER_REJECTED"),
            )
        val docs =
            verdicts.map { (name, v) ->
                RunDocument(
                    name,
                    listOf(
                        version(1, false, lines("ES", "1.00")).copy(facts = DocumentFacts(v.first, v.second)),
                    ),
                )
            }
        val outcome = completed(mapOf("3" to "4.00"), *docs.toTypedArray())
        outcome.result.classifiedVariances
            .map { it.category.code }
            .toSet() shouldBe verdicts.keys
        outcome.raisesPer003 shouldBe false
        val eight = VarianceClass.entries.filter { it != VarianceClass.UNEXPLAINED }.toSet()
        eight.size shouldBe 8
        (VarianceClasses.derived + VarianceClasses.assignable) shouldBe eight
        VarianceClasses.derived.map { it.name }.toSet() shouldBe verdicts.keys
    }

    @Test
    fun `INV-AC-12968 the three cases classify, leave the residual and fix three parameters`() {
        val one =
            completed(mapOf("3" to "1000.00"), doc("X", version(1, false, lines("ES", "1000.00"), "EXCLUDED_TXN")))
        one.result.classifiedVariances
            .single()
            .linkedRecords shouldBe listOf("X")
        one.result.unexplained shouldBe emptyList()
        one.raisesPer003 shouldBe false
        val two =
            completed(
                mapOf("3" to "1500.00"),
                doc("X", version(1, false, lines("ES", "1000.00"), "EXCLUDED_TXN")),
                doc("Y", version(1, false, lines("ES", "500.00"))),
            )
        two.result.classifiedVariances
            .single()
            .amount shouldBe d("1000.00")
        two.result.unexplained
            .single()
            .residual shouldBe d("500.00")
        val three =
            completed(mapOf("1" to "100.00"), doc("S", version(1, false, lines("SR", "100.00"), "SUBMITTABLE")))
        three.result.unexplained
            .single { it.key == "1" }
            .residual shouldBe d("100.00")
        three.raisesPer003 shouldBe true
        ReconciliationRunner::class.java.methods
            .single { it.name == "run" }
            .parameterCount shouldBe 3
    }

    @Test
    fun `INV-AC-12969 a null return gives NoReturn while an all-zero return is compared`() {
        val documents =
            listOf(
                doc("A", version(1, true, lines("SR", "10.00"))),
                doc("B", version(1, false, lines("SR", "20.00"))),
                doc("C", version(1, false, lines("ES", "30.00"), "EXCLUDED_TXN")),
            )
        ReconciliationRunner.run(null, entries, documents) shouldBe RunOutcome.NoReturn("PER-008")
        val zero =
            ReconciliationRunner
                .run(ReturnFigures(1, mapOf("1" to d("0.00"), "3" to d("0.00"))), entries, documents)
                .shouldBeInstanceOf<RunOutcome.Completed>()
        zero.result.comparison
            .map { it.key }
            .containsAll(listOf("1", "3")) shouldBe true
        zero.result.comparison
            .single { it.key == "1" }
            .variance shouldBe d("-10.00")
    }
}
