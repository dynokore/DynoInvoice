package com.dynokore.gst.submission.domain.listing

import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.register.ListFacts
import com.dynokore.gst.submission.domain.register.ListFactsPolicy
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class SubmissionExportTest {
    private val header =
        ExportHeader(
            "ENT-1",
            "M2-1234567-8",
            "2026-Q3",
            Instant.parse("2026-11-02T01:02:03Z"),
            "user-7",
            "state=QUEUED",
        )

    private fun row(
        uuid: String = "d1",
        number: String = "N-1",
        name: String = "Buyer",
        taxable: String = "100.00",
        type: String = "380",
    ) = SubmissionRow(
        docUuid = uuid,
        version = 1,
        submissionType = SubmissionKind.TYPE_1A,
        state = LifecycleState.QUEUED,
        dueDate = LocalDate.parse("2026-11-01"),
        documentNumber = number,
        documentTypeCode = type,
        direction = Direction.SALE,
        counterpartyName = name,
        counterpartyUen = "201234567A",
        transactionDate = LocalDate.parse("2026-10-01"),
        taxableAmount = BigDecimal(taxable),
        gstAmount = BigDecimal("9.00"),
        categoryCodes = setOf("SR"),
        submittedAt = null,
        acknowledgedAt = null,
        acknowledgementId = null,
        originalDueDate = null,
        extendedDueDate = null,
    )

    private val three =
        listOf(
            row("d1"),
            row("d2", type = "381", taxable = "-90.00").copy(gstAmount = BigDecimal("-8.10")),
            row("d3", taxable = "10.5").copy(
                gstAmount = BigDecimal("0.95"),
                state = LifecycleState.SUPERSEDED,
                submittedAt = Instant.parse("2026-10-02T00:00:00Z"),
                acknowledgedAt = Instant.parse("2026-10-02T00:01:00Z"),
                acknowledgementId = "ACK-1",
                originalDueDate = null,
                extendedDueDate = null,
            ),
        )

    private fun lines(text: String) = text.split("\r\n")

    @Test
    fun `INV-AC-16295 table holds row count and totals of SubmissionTotals`() {
        val t = SubmissionExport.table(header, three)
        val totals = SubmissionTotals.of(three)
        t.rowCount shouldBe 3
        t.totalTaxableAmount shouldBe totals.totalTaxableAmount
        t.totalGstAmount shouldBe totals.totalGstAmount
        t.cells.size shouldBe 3
        t.cells[2][9] shouldBe "10.50"
        t.cells[2][15] shouldBe "true"
        t.cells[0][11] shouldBe ""
    }

    @Test
    fun `INV-AC-16295 csv has header line, values, empty line, columns and rows`() {
        val l = lines(SubmissionExport.csv(SubmissionExport.table(header, three)))
        l[0] shouldBe
            "entityRef,gstn,periodRef,generatedAt,generatedBy,filter,rowCount,totalTaxableAmount,totalGstAmount"
        l[1] shouldBe "ENT-1,M2-1234567-8,2026-Q3,2026-11-02T01:02:03Z,user-7,state=QUEUED,3,20.50,1.85"
        l[2] shouldBe ""
        l[3] shouldBe SubmissionExport.COLUMNS.joinToString(",")
        l.size shouldBe 4 + 3 + 1
        l[7] shouldBe ""
        l[5] shouldBe "d2,1,N-1,381,SALE,TYPE_1A,Buyer,201234567A,2026-10-01,-90.00,-8.10,,,,QUEUED,false"
    }

    @Test
    fun `INV-AC-16295 formula characters get an apostrophe and money is unchanged`() {
        fun cell(name: String) =
            lines(SubmissionExport.csv(SubmissionExport.table(header, listOf(row(name = name)))))[4]
        cell("=HYPERLINK(\"x\")") shouldBe
            "d1,1,N-1,380,SALE,TYPE_1A,\"'=HYPERLINK(\"\"x\"\")\",201234567A,2026-10-01,100.00,9.00,,,,QUEUED,false"
        for (c in listOf("+a", "-a", "@a", "\ta")) {
            cell(c).split(",")[6] shouldBe "'$c"
        }
        cell("A, B").contains(",\"A, B\",") shouldBe true
        cell("A\nB").contains(",\"A\nB\",") shouldBe true
        val neg = lines(SubmissionExport.csv(SubmissionExport.table(header, three)))[5]
        neg.contains(",-90.00,-8.10,") shouldBe true
    }

    @Test
    fun `INV-AC-16295 document number starting with minus is neutralised`() {
        val l = lines(SubmissionExport.csv(SubmissionExport.table(header, listOf(row(number = "-12")))))
        l[4].split(",")[2] shouldBe "'-12"
    }

    @Test
    fun `INV-AC-16295 empty rows give four lines and zero totals`() {
        val t = SubmissionExport.table(header, emptyList())
        t.rowCount shouldBe 0
        t.totalTaxableAmount shouldBe BigDecimal("0.00")
        t.totalGstAmount shouldBe BigDecimal("0.00")
        val l = lines(SubmissionExport.csv(t))
        l shouldBe
            listOf(
                "entityRef,gstn,periodRef,generatedAt,generatedBy,filter,rowCount,totalTaxableAmount,totalGstAmount",
                "ENT-1,M2-1234567-8,2026-Q3,2026-11-02T01:02:03Z,user-7,state=QUEUED,0,0.00,0.00",
                "",
                SubmissionExport.COLUMNS.joinToString(","),
                "",
            )
    }

    @Test
    fun `INV-AC-16295 empty filter gives an empty field and output is deterministic`() {
        val h = header.copy(filter = "")
        val l = lines(SubmissionExport.csv(SubmissionExport.table(h, three)))
        l[1] shouldBe "ENT-1,M2-1234567-8,2026-Q3,2026-11-02T01:02:03Z,user-7,,3,20.50,1.85"
        SubmissionExport.table(header, three) shouldBe SubmissionExport.table(header, three)
        SubmissionExport.csv(SubmissionExport.table(header, three)) shouldBe
            SubmissionExport.csv(SubmissionExport.table(header, three))
    }

    @Test
    fun `INV-AC-16295 blank generatedBy and blank gstn are refused`() {
        assertThrows<IllegalArgumentException> { header.copy(generatedBy = " ") }
        assertThrows<IllegalArgumentException> { header.copy(gstn = "") }
    }

    @Test
    fun `INV-AC-16295 COLUMNS are the sixteen contract columns`() {
        SubmissionExport.COLUMNS shouldBe
            listOf(
                "docUuid",
                "version",
                "documentNumber",
                "documentTypeCode",
                "direction",
                "submissionType",
                "counterpartyName",
                "counterpartyUen",
                "transactionDate",
                "taxableAmount",
                "gstAmount",
                "submittedAt",
                "acknowledgedAt",
                "acknowledgementId",
                "state",
                "superseded",
            )
        SubmissionExport.COLUMNS.size shouldBe 16
    }

    private fun factsOf(r: SubmissionRow) =
        ListFacts(
            r.documentNumber,
            r.documentTypeCode,
            r.direction,
            r.counterpartyName,
            r.counterpartyUen,
            r.taxableAmount,
            r.gstAmount,
            r.categoryCodes,
        )

    @Test
    fun `INV-AC-23395 g with content allowed the table equals the two-argument table`() {
        SubmissionExport.table(header, three, true) shouldBe SubmissionExport.table(header, three)
    }

    @Test
    fun `INV-AC-23395 h without content the five masked cells are empty exactly where the policy withholds`() {
        val full = SubmissionExport.table(header, three, true)
        val masked = SubmissionExport.table(header, three, false)
        val names = listOf("documentNumber", "counterpartyName", "counterpartyUen", "taxableAmount", "gstAmount")
        val kept =
            listOf(
                "docUuid",
                "version",
                "documentTypeCode",
                "direction",
                "submissionType",
                "transactionDate",
                "state",
                "superseded",
            )
        three.forEachIndexed { r, row ->
            val served = ListFactsPolicy.mask(factsOf(row), false)
            val withheld =
                mapOf(
                    "documentNumber" to (served.documentNumber == null),
                    "counterpartyName" to (served.counterpartyName == null),
                    "counterpartyUen" to (served.counterpartyUen == null),
                    "taxableAmount" to (served.taxableAmount == null),
                    "gstAmount" to (served.gstAmount == null),
                )
            names.forEach { n ->
                val i = SubmissionExport.COLUMNS.indexOf(n)
                (masked.cells[r][i] == "") shouldBe withheld.getValue(n)
                withheld.getValue(n) shouldBe true
            }
            kept.forEach { n ->
                val i = SubmissionExport.COLUMNS.indexOf(n)
                masked.cells[r][i] shouldBe full.cells[r][i]
            }
        }
    }

    @Test
    fun `INV-AC-23395 i without content the count and totals stay and the csv holds no masked value`() {
        val full = SubmissionExport.table(header, three, true)
        val masked = SubmissionExport.table(header, three, false)
        masked.rowCount shouldBe full.rowCount
        masked.totalTaxableAmount shouldBe full.totalTaxableAmount
        masked.totalGstAmount shouldBe full.totalGstAmount
        val csv = SubmissionExport.csv(masked)
        csv.contains("Buyer") shouldBe false
        csv.contains("N-1") shouldBe false
        csv.contains("201234567A") shouldBe false
    }
}
