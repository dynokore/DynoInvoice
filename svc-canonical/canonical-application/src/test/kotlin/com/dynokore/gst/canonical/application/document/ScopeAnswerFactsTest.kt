package com.dynokore.gst.canonical.application.document

import com.dynokore.gst.canonical.domain.document.Direction
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** INV-AC-21596: the facts of the scope answer, the hold rules and the capture, over fakes. */
class ScopeAnswerFactsTest {
    private val t1 = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val d0320 = LocalDate.of(2026, 3, 20)

    private fun line(
        category: String? = "SR",
        rate: String? = "9.00",
        scope: String? = "S-2026.1",
        mapping: String? = "M-3",
        type: String? = "380",
    ) = LineAnswer.Verdict("IN_SCOPE", category, rate, scope, mapping, null, type)

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
    fun `INV-AC-21596 K3 two agreeing lines give the facts and the lineTax in line order`() {
        answer(line(), line()) shouldBe
            ScopeAnswer.Verdict(
                scopeVerdict = "IN_SCOPE",
                documentTypeCode = "380",
                scopePackVersion = "S-2026.1",
                mappingPackVersion = "M-3",
                mdePackVersion = null,
                lineTax = listOf(LineTaxFact("SR", "9.00"), LineTaxFact("SR", "9.00")),
            )
    }

    @Test
    fun `INV-AC-21596 K3 lineTax keeps the line order of differing lines`() {
        val a = answer(line(category = "SR", rate = "9.00"), line(category = "ZR", rate = "0.00"))
        (a as ScopeAnswer.Verdict).lineTax shouldBe listOf(LineTaxFact("SR", "9.00"), LineTaxFact("ZR", "0.00"))
    }

    @Test
    fun `INV-AC-21596 K4 lines disagreeing on the scope pack version are Unavailable`() {
        answer(line(), line(scope = "S-2026.2")) shouldBe ScopeAnswer.Unavailable
        answer(line(), line(scope = null)) shouldBe ScopeAnswer.Unavailable
    }

    @Test
    fun `INV-AC-21596 K5 lines disagreeing on the mapping pack version are Unavailable`() {
        answer(line(), line(mapping = "M-4")) shouldBe ScopeAnswer.Unavailable
    }

    @Test
    fun `INV-AC-21596 K5 lines disagreeing on the document type code are Unavailable`() {
        answer(line(), line(type = "381")) shouldBe ScopeAnswer.Unavailable
    }

    @Test
    fun `INV-AC-21596 K5 lines disagreeing on the MDE pack version are Unavailable`() {
        answer(line(), line().copy(mdePackVersion = "D-1")) shouldBe ScopeAnswer.Unavailable
    }

    @Test
    fun `INV-AC-21596 K6 a line without a category code is Unavailable`() {
        answer(line(), line(category = null)) shouldBe ScopeAnswer.Unavailable
        answer(line(), line(category = " ")) shouldBe ScopeAnswer.Unavailable
    }

    @Test
    fun `INV-AC-21596 K6 a line with a category code and no rate is Unavailable`() {
        answer(line(), line(rate = null)) shouldBe ScopeAnswer.Unavailable
        answer(line(), line(rate = "")) shouldBe ScopeAnswer.Unavailable
    }

    private class Delegate(
        vararg answers: ScopeAnswer,
    ) : ScopeEvaluation {
        private val queue = answers.toMutableList()
        val seen = mutableListOf<Triple<RecordDocumentCommand, String?, LocalDate>>()

        override fun evaluate(
            command: RecordDocumentCommand,
            periodRef: String?,
            transactionDate: LocalDate,
        ): ScopeAnswer {
            seen += Triple(command, periodRef, transactionDate)
            return queue.removeFirst()
        }
    }

    private val verdict = ScopeAnswer.Verdict("IN_SCOPE", "380", "S-1", "M-1", null, listOf(LineTaxFact("SR", "9.00")))

    @Test
    fun `INV-AC-21596 K7 the capture returns the delegate answer unchanged and take gives the Verdict`() {
        val delegate = Delegate(verdict, ScopeAnswer.Unavailable)
        val capture = ScopeAnswerCapture(delegate)
        val c = command()
        capture.evaluate(c, "2026-03", d0320) shouldBe verdict
        delegate.seen shouldBe listOf(Triple(c, "2026-03", d0320))
        capture.take(c) shouldBe verdict
    }

    @Test
    fun `INV-AC-21596 K7 after an Unavailable evaluate take returns null`() {
        val capture = ScopeAnswerCapture(Delegate(verdict, ScopeAnswer.Unavailable))
        val c = command()
        capture.evaluate(c, null, d0320) shouldBe verdict
        capture.evaluate(c, null, d0320) shouldBe ScopeAnswer.Unavailable
        capture.take(c) shouldBe null
    }

    @Test
    fun `INV-AC-21596 K8 the second take for the same command returns null`() {
        val capture = ScopeAnswerCapture(Delegate(verdict))
        val c = command()
        capture.evaluate(c, null, d0320)
        capture.take(c) shouldBe verdict
        capture.take(c) shouldBe null
    }

    @Test
    fun `INV-AC-21596 K8 take for another command returns null and clears the slot`() {
        val capture = ScopeAnswerCapture(Delegate(verdict))
        val c = command()
        capture.evaluate(c, null, d0320)
        capture.take(command(key = "K2")) shouldBe null
        capture.take(c) shouldBe null
    }

    @Test
    fun `INV-AC-21596 K8 take from another thread returns null`() {
        val capture = ScopeAnswerCapture(Delegate(verdict))
        val c = command()
        capture.evaluate(c, null, d0320)
        val other = AtomicReference<ScopeAnswer.Verdict?>(verdict)
        Thread { other.set(capture.take(c)) }.also { it.start() }.join()
        other.get() shouldBe null
        capture.take(c) shouldBe verdict
    }
}
