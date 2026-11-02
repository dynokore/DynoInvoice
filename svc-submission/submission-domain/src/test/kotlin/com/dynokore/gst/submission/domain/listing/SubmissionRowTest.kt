package com.dynokore.gst.submission.domain.listing

import com.dynokore.gst.submission.domain.ordering.LifecycleState
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class SubmissionRowTest {
    private fun row(
        uuid: String,
        version: Int = 1,
        state: LifecycleState = LifecycleState.QUEUED,
    ) = SubmissionRow(
        docUuid = uuid,
        version = version,
        submissionType = SubmissionKind.TYPE_1A,
        state = state,
        dueDate = LocalDate.parse("2026-11-01"),
        documentNumber = "N-$uuid",
        documentTypeCode = "380",
        direction = Direction.SALE,
        counterpartyName = "Buyer",
        counterpartyUen = "201234567A",
        transactionDate = LocalDate.parse("2026-10-01"),
        taxableAmount = BigDecimal("100.00"),
        gstAmount = BigDecimal("9.00"),
        categoryCodes = setOf("SR"),
        submittedAt = null,
        acknowledgedAt = null,
        acknowledgementId = null,
    )

    private fun SubmissionRow.on(
        due: String,
        tx: String,
    ) = copy(dueDate = LocalDate.parse(due), transactionDate = LocalDate.parse(tx))

    private val v1 = row("b", 1, LifecycleState.SUPERSEDED).on("2026-11-05", "2026-10-03")
    private val v2 =
        row("b", 2, LifecycleState.ACKNOWLEDGED)
            .on("2026-11-05", "2026-10-03")
            .copy(acknowledgedAt = Instant.EPOCH, acknowledgementId = "A1")
    private val credit = row("a", 1, LifecycleState.SENT).on("2026-11-02", "2026-10-05").copy(documentTypeCode = "381")
    private val queued = row("c", 1, LifecycleState.QUEUED).on("2026-11-02", "2026-10-01")
    private val all = listOf(v1, v2, credit, queued)

    @Test
    fun `INV-AC-16292 every row is kept and only the superseded version carries the marker`() {
        val out = SubmissionRows.assemble(listOf(v1, v2, credit, queued))
        out.size shouldBe 4
        out.toSet() shouldBe all.toSet()
        out.filter { it.superseded } shouldBe listOf(v1)
    }

    @Test
    fun `INV-AC-16292 three rows of one period with superseded true for version 1 only`() {
        val out = SubmissionRows.assemble(listOf(v1, v2, credit))
        out.size shouldBe 3
        out.map { it.superseded } shouldBe out.map { it == v1 }
    }

    @Test
    fun `INV-AC-16292 the six permutations of three rows give the same output`() {
        val rows = listOf(v1, credit, queued)
        val perms =
            listOf(
                listOf(0, 1, 2),
                listOf(0, 2, 1),
                listOf(1, 0, 2),
                listOf(1, 2, 0),
                listOf(2, 0, 1),
                listOf(2, 1, 0),
            )
        for (sort in listOf(SubmissionRows.DEFAULT_SORT, SortKey.parse("dueDate"), SortKey.parse("-dueDate"))) {
            val expected = SubmissionRows.assemble(rows, sort)
            for (p in perms) SubmissionRows.assemble(p.map { rows[it] }, sort) shouldBe expected
        }
    }

    @Test
    fun `INV-AC-16292 default and transactionDate sort ascending with ties by docUuid then version`() {
        // tx: queued 10-01, v1/v2 10-03, credit 10-05
        SubmissionRows.assemble(all.reversed()) shouldBe listOf(queued, v1, v2, credit)
        SubmissionRows.assemble(all, SortKey.parse("transactionDate")) shouldBe listOf(queued, v1, v2, credit)
    }

    @Test
    fun `INV-AC-16292 dueDate sort ties by docUuid then version`() {
        // due: credit and queued 11-02 (tie, a before c), v1/v2 11-05
        SubmissionRows.assemble(all.reversed(), SortKey.parse("dueDate")) shouldBe listOf(credit, queued, v1, v2)
    }

    @Test
    fun `INV-AC-16292 dueDate descending keeps the tie-break ascending`() {
        SubmissionRows.assemble(all.reversed(), SortKey.parse("-dueDate")) shouldBe listOf(v1, v2, credit, queued)
    }

    @Test
    fun `INV-AC-16292 a repeated docUuid and version throws`() {
        assertThrows<IllegalArgumentException> { SubmissionRows.assemble(listOf(v1, v1.copy(documentNumber = "X"))) }
    }

    @Test
    fun `INV-AC-16292 sort key parse accepts exactly the contract pattern`() {
        SortKey.parse("-dueDate") shouldBe SortKey(SortField.DUE_DATE, true)
        SortKey.parse("transactionDate") shouldBe SortKey(SortField.TRANSACTION_DATE, false)
        for (bad in listOf(
            "duedate",
            "+dueDate",
            "dueDate ",
            "",
            " dueDate",
            "dueDate\n",
            "--dueDate",
            "dueDate,transactionDate",
        )) {
            assertThrows<IllegalArgumentException>(bad) { SortKey.parse(bad) }
        }
    }

    @Test
    fun `INV-AC-16292 an invalid row throws`() {
        assertThrows<IllegalArgumentException> { row("a").copy(documentTypeCode = "379") }
        assertThrows<IllegalArgumentException> { row("a").copy(version = 0) }
        assertThrows<IllegalArgumentException> { row("a").copy(taxableAmount = BigDecimal("1.001")) }
        assertThrows<IllegalArgumentException> { row("a").copy(acknowledgedAt = Instant.EPOCH) }
        assertThrows<IllegalArgumentException> { row("a").copy(acknowledgementId = "A1") }
        assertThrows<IllegalArgumentException> { row("a").copy(docUuid = " ") }
    }

    @Test
    fun `INV-AC-16292 the enums have the contract names in order`() {
        SubmissionKind.entries.map { it.name } shouldBe listOf("TYPE_1A", "TYPE_1B", "TYPE_2", "TYPE_3")
        Direction.entries.map { it.name } shouldBe listOf("SALE", "PURCHASE")
    }
}
