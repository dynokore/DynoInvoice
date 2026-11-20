package com.dynokore.gst.canonical.application.document

import com.dynokore.gst.canonical.domain.document.Direction
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class ScopeEvaluationServiceTest {
    private val t1 = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val d0320 = LocalDate.of(2026, 3, 20)
    private val d0401 = LocalDate.of(2026, 4, 1)
    private val scheme = SchemeFacts(listOf("ARCS"), true)
    private val lineTax = listOf(LineTaxFact("SR", "9.00"), LineTaxFact("ZR", "0.00"))

    private class Run(
        sourceUp: Boolean,
        scheme: SchemeFacts,
        t1: UUID,
        d0320: LocalDate,
    ) {
        val reads = mutableListOf<LocalDate>()
        val lookups = mutableListOf<CounterpartyLookup>()
        val calls = mutableListOf<LineScopeFacts>()
        val combined = mutableListOf<List<String>>()
        val service =
            ScopeEvaluationService(
                constitutions =
                    object : EntityConstitutions {
                        override fun constitutionOf(
                            tenantId: UUID,
                            entityRef: String,
                        ): String? = if (tenantId == t1 && entityRef == "E1") "PRIVATE_LIMITED" else null
                    },
                schemes =
                    SchemeElections { t, e, on ->
                        reads += on
                        if (t == t1 && e == "E1" && on == d0320) listOf("ARCS") else emptyList()
                    },
                counterparty =
                    CounterpartySchemeSource {
                        lookups += it
                        if (sourceUp) SchemeFactsAnswer.Found(scheme) else SchemeFactsAnswer.Unavailable
                    },
                evaluator =
                    LineEvaluator {
                        calls += it
                        when (it.internalTaxCode) {
                            "SR-STD" -> LineAnswer.Verdict("IN_SCOPE", "SR", "9.00")
                            "SR-SUP" -> LineAnswer.Verdict("IN_SCOPE", "SR", "9.00", supplyType = "ZERO_RATED")
                            "ZR-EXP" -> LineAnswer.Verdict("EXCLUDED_TXN", "ZR", "0.00")
                            else -> LineAnswer.Unavailable
                        }
                    },
                combination = {
                    combined += it
                    "COMBINED"
                },
            )
    }

    private fun run(sourceUp: Boolean = true) = Run(sourceUp, scheme, t1, d0320)

    private fun c1(
        vararg codes: String,
        entity: String = "E1",
        direction: Direction? = Direction.SALE,
        buyer: PartyIdentifier = PartyIdentifier("UEN", "201954321B"),
        supplier: PartyIdentifier = PartyIdentifier("UEN", "200000001A"),
    ) = RecordDocumentCommand(
        tenantId = t1,
        entityRef = entity,
        sourceSystem = "ERP",
        sourceKey = "K1",
        sourceRevision = 1,
        transactionDate = d0320,
        direction = direction,
        supplier = supplier,
        buyer = buyer,
        invoiceBacked = true,
        lines = codes.map { RecordLine(it) },
    )

    private fun facts(
        code: String,
        on: LocalDate = d0320,
    ) = LineScopeFacts("E1", on, code, listOf("ARCS"), scheme)

    @Test
    fun `INV-AC-21472 C1 calls the evaluator per line in order and combines`() {
        val r = run()
        r.service.evaluate(c1("SR-STD", "ZR-EXP"), "2026-Q1", d0320) shouldBe
            ScopeAnswer.Verdict("COMBINED", lineTax = lineTax)
        r.calls shouldBe listOf(facts("SR-STD"), facts("ZR-EXP"))
        r.combined shouldBe listOf(listOf("IN_SCOPE", "EXCLUDED_TXN"))
        r.reads shouldBe listOf(d0320)
        r.lookups shouldBe listOf(CounterpartyLookup("E1", "PRIVATE_LIMITED", "UEN", "201954321B", d0320))
    }

    @Test
    fun `INV-AC-24847 the supplyType of each line answer is copied unchanged to its LineTaxFact and may be null`() {
        val answer = run().service.evaluate(c1("SR-SUP", "SR-STD"), "2026-Q1", d0320)
        (answer as ScopeAnswer.Verdict).lineTax shouldBe
            listOf(LineTaxFact("SR", "9.00", "ZERO_RATED"), LineTaxFact("SR", "9.00", null))
    }

    @Test
    fun `INV-AC-21472 C2 stops at the first Unavailable`() {
        val r = run()
        r.service.evaluate(c1("SR-STD", "BAD", "ZR-EXP"), "2026-Q1", d0320) shouldBe ScopeAnswer.Unavailable
        r.calls shouldBe listOf(facts("SR-STD"), facts("BAD"))
        r.combined shouldBe emptyList()
    }

    @Test
    fun `INV-AC-21472 C3 a document without a line is not evaluated`() {
        val r = run()
        r.service.evaluate(c1(), "2026-Q1", d0320) shouldBe ScopeAnswer.Unavailable
        r.calls shouldBe emptyList()
        r.combined shouldBe emptyList()
    }

    @Test
    fun `INV-AC-21472 C4 source unavailable gives Unavailable and no call`() {
        val r = run(sourceUp = false)
        r.service.evaluate(c1("SR-STD", "ZR-EXP"), "2026-Q1", d0320) shouldBe ScopeAnswer.Unavailable
        r.calls shouldBe emptyList()
        r.combined shouldBe emptyList()
    }

    @Test
    fun `INV-AC-21472 C5 unknown entity skips the source and has no counterparty facts`() {
        val r = run()
        r.service.evaluate(c1("SR-STD", "ZR-EXP", entity = "E9"), "2026-Q1", d0320) shouldBe
            ScopeAnswer.Verdict("COMBINED", lineTax = lineTax)
        r.lookups shouldBe emptyList()
        r.calls.map { it.counterpartyScheme } shouldBe listOf(null, null)
    }

    @Test
    fun `INV-AC-21472 C6 PURCHASE looks up the supplier`() {
        val r = run()
        r.service.evaluate(c1("SR-STD", direction = Direction.PURCHASE), "2026-Q1", d0320)
        r.lookups shouldBe listOf(CounterpartyLookup("E1", "PRIVATE_LIMITED", "UEN", "200000001A", d0320))
    }

    @Test
    fun `INV-AC-21472 C7 the date is the argument not the command date`() {
        val r = run()
        r.service.evaluate(c1("SR-STD", "ZR-EXP"), "2026-Q1", d0401)
        r.reads shouldBe listOf(d0401)
        r.calls.map { it.transactionDate } shouldBe listOf(d0401, d0401)
        r.calls.map { it.electedSchemes } shouldBe listOf(emptyList(), emptyList())
    }

    private fun expectedLookup(
        d: Direction?,
        cmd: RecordDocumentCommand,
        c: String?,
    ): CounterpartyLookup? {
        val party =
            when (d) {
                Direction.SALE -> cmd.buyer
                Direction.PURCHASE -> cmd.supplier
                null -> null
            }
        val usable = party != null && party.scheme.isNotBlank() && party.value.isNotBlank()
        return if (usable && c != null) {
            CounterpartyLookup("E1", c, party.scheme, party.value, d0320)
        } else {
            null
        }
    }

    @Test
    fun `INV-AC-21472 lookup equals the rule of RecordDocumentService for every combination`() {
        val ok = PartyIdentifier("UEN", "X1")
        val blank = PartyIdentifier(" ", "")
        val cases =
            listOf(Direction.SALE, Direction.PURCHASE, null).flatMap { d ->
                listOf(ok, blank).flatMap { b ->
                    listOf(ok, blank).flatMap { s -> listOf("PRIVATE_LIMITED", null).map { c -> listOf(d, b, s, c) } }
                }
            }
        cases.size shouldBe 24
        cases.forEach {
            val d = it[0] as Direction?
            val cmd = c1(direction = d, buyer = it[1] as PartyIdentifier, supplier = it[2] as PartyIdentifier)
            CounterpartyLookups.of(cmd, it[3] as String?) shouldBe expectedLookup(d, cmd, it[3] as String?)
        }
    }

    private fun combine(vararg v: String) = LineVerdictCombination.combine(v.toList())

    @Test
    fun `INV-AC-22742 K1 in scope alone`() {
        combine("IN_SCOPE") shouldBe "IN_SCOPE"
    }

    @Test
    fun `INV-AC-22742 K2 out of scope alone`() {
        combine("OUT_OF_SCOPE") shouldBe "OUT_OF_SCOPE"
    }

    @Test
    fun `INV-AC-22742 K3 excluded alone`() {
        combine("EXCLUDED_TXN") shouldBe "EXCLUDED_TXN"
    }

    @Test
    fun `INV-AC-22742 K4 in scope beats out of scope`() {
        combine("OUT_OF_SCOPE", "IN_SCOPE") shouldBe "IN_SCOPE"
    }

    @Test
    fun `INV-AC-22742 K5 in scope beats the rest`() {
        combine("IN_SCOPE", "EXCLUDED_TXN", "OUT_OF_SCOPE") shouldBe "IN_SCOPE"
    }

    @Test
    fun `INV-AC-22742 K6 out of scope before excluded`() {
        combine("EXCLUDED_TXN", "OUT_OF_SCOPE") shouldBe "OUT_OF_SCOPE"
    }

    @Test
    fun `INV-AC-22742 K7 out of scope before excluded reversed`() {
        combine("OUT_OF_SCOPE", "EXCLUDED_TXN") shouldBe "OUT_OF_SCOPE"
    }

    @Test
    fun `INV-AC-22742 K8 all excluded`() {
        combine("EXCLUDED_TXN", "EXCLUDED_TXN") shouldBe "EXCLUDED_TXN"
    }

    @Test
    fun `INV-AC-22742 K9 order does not matter`() {
        val all = listOf("IN_SCOPE", "OUT_OF_SCOPE", "EXCLUDED_TXN")
        val perms =
            all.flatMap { a -> (all - a).flatMap { b -> (all - a - b).map { c -> listOf(a, b, c) } } }
        perms.size shouldBe 6
        perms.forEach { LineVerdictCombination.combine(it) shouldBe "IN_SCOPE" }
    }

    @Test
    fun `INV-AC-22742 K10 empty list throws`() {
        shouldThrow<IllegalArgumentException> { LineVerdictCombination.combine(emptyList()) }
    }

    @Test
    fun `INV-AC-22742 K11 SUBMITTABLE is refused`() {
        shouldThrow<IllegalArgumentException> { combine("IN_SCOPE", "SUBMITTABLE") }
    }

    @Test
    fun `INV-AC-22742 K12 no case folding`() {
        shouldThrow<IllegalArgumentException> { combine("in_scope") }
    }
}
