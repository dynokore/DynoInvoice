package com.dynokore.gst.canonical.application.document

import com.dynokore.gst.canonical.domain.document.Direction
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** INV-AC-22910: the applied packs of the scope answer, over fakes of two lines. */
class ScopeAnswerAppliedPacksTest {
    private val t1 = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val d0320 = LocalDate.of(2026, 3, 20)

    private val base =
        listOf(AppliedPackFact("SCOPE", "gst-scope", "2026.3"), AppliedPackFact("MAPPING", "MAP-0042", "7"))

    private fun line(
        category: String? = "SR",
        rate: String? = "9.00",
        scope: String? = "S-2026.1",
        mapping: String? = "M-3",
        type: String? = "380",
    ) = LineAnswer.Verdict("IN_SCOPE", category, rate, scope, mapping, null, type, base)

    private fun withPacks(packs: List<AppliedPackFact>) = line().copy(appliedPacks = packs)

    private fun service(vararg answers: LineAnswer): ScopeEvaluationService {
        val queue = answers.toMutableList()
        return ScopeEvaluationService(
            constitutions =
                object : EntityConstitutions {
                    override fun constitutionOf(
                        tenantId: UUID,
                        entityRef: String,
                    ): String? = "PRIVATE_LIMITED"
                },
            schemes = SchemeElections { _, _, _ -> listOf("ARCS") },
            counterparty = CounterpartySchemeSource { SchemeFactsAnswer.Found(SchemeFacts(listOf("ARCS"), true)) },
            evaluator = LineEvaluator { queue.removeFirst() },
        )
    }

    private fun command(
        key: String = "K1",
        lines: Int = 2,
    ) = RecordDocumentCommand(
        tenantId = t1,
        entityRef = "E1",
        sourceSystem = "ERP",
        sourceKey = key,
        sourceRevision = 1,
        transactionDate = d0320,
        direction = Direction.SALE,
        supplier = PartyIdentifier("UEN", "200000001A"),
        buyer = PartyIdentifier("UEN", "201954321B"),
        invoiceBacked = true,
        lines = List(lines) { RecordLine("SR-STD") },
    )

    private fun answer(vararg answers: LineAnswer) = service(*answers).evaluate(command(), null, d0320)

    @Test
    fun `INV-AC-22910 S1 equal lists are carried`() {
        (answer(line(), line()) as ScopeAnswer.Verdict).appliedPacks shouldBe base
    }

    @Test
    fun `INV-AC-22910 S2 lists differing in one identifier give Unavailable`() {
        val other = listOf(base[0], AppliedPackFact("MAPPING", "MAP-0043", "7"))
        answer(line(), withPacks(other)) shouldBe ScopeAnswer.Unavailable
    }

    @Test
    fun `INV-AC-22910 S3 lists differing in order, length or type give Unavailable`() {
        answer(line(), withPacks(base.reversed())) shouldBe ScopeAnswer.Unavailable
        answer(line(), withPacks(base.take(1))) shouldBe ScopeAnswer.Unavailable
        answer(line(), withPacks(emptyList())) shouldBe ScopeAnswer.Unavailable
        answer(line(), withPacks(listOf(base[0], AppliedPackFact("MDE", "MAP-0042", "7")))) shouldBe
            ScopeAnswer.Unavailable
    }

    @Test
    fun `INV-AC-22910 S4 lists differing in version give Unavailable`() {
        answer(line(), withPacks(listOf(base[0], AppliedPackFact("MAPPING", "MAP-0042", "8")))) shouldBe
            ScopeAnswer.Unavailable
    }
}
