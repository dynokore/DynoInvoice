package com.dynokore.gst.canonical.application.document

import com.dynokore.gst.canonical.domain.dating.DateBasis
import com.dynokore.gst.canonical.domain.dating.PeriodSpan
import com.dynokore.gst.canonical.domain.document.Direction
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.UUID

class RecordDocumentResultBuilderTest {
    private val t1 = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val d1 = UUID.fromString("00000000-0000-0000-0000-0000000000d1")
    private val d2 = UUID.fromString("00000000-0000-0000-0000-0000000000d2")
    private val q1 = PeriodSpan("2026-Q1", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31))
    private val spanB = PeriodSpan("B", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 6, 30))
    private val c1 =
        RecordDocumentCommand(
            tenantId = t1,
            entityRef = "E1",
            sourceSystem = "erp",
            sourceKey = "k",
            sourceRevision = 1,
            transactionDate = LocalDate.of(2026, 3, 20),
            direction = Direction.SALE,
            supplier = PartyIdentifier("UEN", "S"),
            buyer = PartyIdentifier("UEN", "B"),
            invoiceBacked = true,
        )

    private class Calls(
        val periodRef: String?,
        val date: LocalDate,
    )

    private val electionReads = mutableListOf<String>()
    private val scopeCalls = mutableListOf<Calls>()
    private val maps = mutableListOf<Map<String, Any?>>()
    private var rulesUp = true

    private val builder =
        RecordDocumentResultBuilder(
            periods = { _, e ->
                when (e) {
                    "E1" -> listOf(q1)
                    "E3" -> listOf(q1, spanB)
                    else -> emptyList()
                }
            },
            elections = { _, e, _ ->
                electionReads += e
                if (e == "E1") DateBasis.POSTING_DATE else null
            },
            scope = { _, p, d ->
                scopeCalls += Calls(p, d)
                if (rulesUp) ScopeAnswer.Verdict("SUBMITTABLE") else ScopeAnswer.Unavailable
            },
            versions = { if (it == d2) 3 else 1 },
            mapper = {
                maps += it
                "json#${maps.size}"
            },
        )

    private fun built(
        c: RecordDocumentCommand,
        d: UUID = d1,
    ): Map<String, Any?> {
        val expected = "json#${maps.size + 1}"
        builder.build(c, d) shouldBe expected
        return maps.last()
    }

    @Suppress("UNCHECKED_CAST")
    private fun reasons(m: Map<String, Any?>) = m["reasons"] as List<Map<String, String>>

    private fun checkReason(r: Map<String, String>) {
        r["field"] shouldBe "/"
        r["message"].orEmpty().isNotBlank() shouldBe true
        r.keys shouldBe setOf("code", "field", "message")
    }

    private val purchase =
        c1.copy(
            direction = Direction.PURCHASE,
            postingTimestamp = OffsetDateTime.parse("2026-04-01T00:30:00+08:00"),
        )

    @Test
    fun `INV-AC-21412 first the sale is answered with the date, the period and no reasons`() {
        val m = built(c1)
        m["docUuid"] shouldBe d1.toString()
        m["version"] shouldBe 1
        m["scopeVerdict"] shouldBe "SUBMITTABLE"
        m["transactionDate"] shouldBe "2026-03-20"
        m["prescribedPeriodRef"] shouldBe "2026-Q1"
        m.containsKey("reasons") shouldBe false
        electionReads shouldBe emptyList()
    }

    @Test
    fun `INV-AC-21412 second the posting date in Singapore time falls outside every span and holds PER-009`() {
        val m = built(purchase)
        m["transactionDate"] shouldBe "2026-04-01"
        m["prescribedPeriodRef"] shouldBe ""
        m["scopeVerdict"] shouldBe "SUBMITTABLE"
        reasons(m).map { it["code"] } shouldBe listOf("PER-009")
        checkReason(reasons(m).single())
    }

    @Test
    fun `INV-AC-21412 third no election holds PER-011 and keeps the request date`() {
        val m = built(purchase.copy(entityRef = "E2"))
        reasons(m).map { it["code"] } shouldBe listOf("PER-011")
        checkReason(reasons(m).single())
        m["transactionDate"] shouldBe "2026-03-20"
        m["prescribedPeriodRef"] shouldBe ""
    }

    @Test
    fun `INV-AC-21412 fourth two covering spans throw PeriodInconsistent SRT-010 and return no text`() {
        val e = shouldThrow<PeriodInconsistent> { builder.build(c1.copy(entityRef = "E3"), d1) }
        e.failureCode shouldBe "SRT-010"
        maps shouldBe emptyList()
    }

    @Test
    fun `INV-AC-21412 fifth the posting date decides without a timestamp and RecordRefused SRT-001 is thrown`() {
        val e = shouldThrow<RecordRefused> { builder.build(purchase.copy(postingTimestamp = null), d1) }
        e.code shouldBe "SRT-001"
        maps shouldBe emptyList()
    }

    @Test
    fun `INV-AC-21412 sixth rules unavailable holds ING-016 once`() {
        rulesUp = false
        val m = built(c1)
        reasons(m).map { it["code"] } shouldBe listOf("ING-016")
        checkReason(reasons(m).single())
    }

    @Test
    fun `INV-AC-21412 seventh the version comes from the document versions port`() {
        built(c1, d2)["version"] shouldBe 3
    }

    @Test
    fun `INV-AC-21412 eighth the scope evaluation receives the derived date and the period reference`() {
        built(c1)
        scopeCalls.single().periodRef shouldBe "2026-Q1"
        scopeCalls.single().date shouldBe LocalDate.of(2026, 3, 20)
        scopeCalls.clear()
        built(purchase)
        scopeCalls.single().date shouldBe LocalDate.of(2026, 4, 1)
        (scopeCalls.single().date == c1.transactionDate) shouldBe false
        scopeCalls.single().periodRef shouldBe null
    }

    @Test
    fun `INV-AC-21412 the span end is inclusive and the election is read only for a purchase`() {
        built(c1.copy(transactionDate = LocalDate.of(2026, 3, 31)))["prescribedPeriodRef"] shouldBe "2026-Q1"
        electionReads shouldBe emptyList()
        built(purchase)
        electionReads shouldBe listOf("E1")
    }

    @Test
    fun `INV-AC-21412 an absent direction is a SALE and no election is read`() {
        built(c1.copy(direction = null))["transactionDate"] shouldBe "2026-03-20"
        electionReads shouldBe emptyList()
    }

    @Test
    fun `INV-AC-21412 a zoneless posting is read in the source zone and a bad zone id is refused SRT-001`() {
        val local = LocalDateTime.parse("2026-03-31T23:30:00")
        val zoneless = purchase.copy(postingTimestamp = null, localPostingTimestamp = local)
        built(zoneless)["transactionDate"] shouldBe "2026-03-31"
        built(zoneless.copy(sourceTimeZone = "UTC"))["transactionDate"] shouldBe "2026-04-01"
        val bad = zoneless.copy(sourceTimeZone = "Not/AZone")
        shouldThrow<RecordRefused> { builder.build(bad, d1) }.code shouldBe "SRT-001"
    }
}
