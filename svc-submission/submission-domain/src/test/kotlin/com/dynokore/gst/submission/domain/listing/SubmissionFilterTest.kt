package com.dynokore.gst.submission.domain.listing

import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.reconciliation.ReconciliationBucket
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class SubmissionFilterTest {
    private data class Extra(
        val gst: String = "9.00",
        val categories: Set<String> = setOf("SR"),
        val acked: Boolean = false,
    )

    private fun row(
        uuid: String,
        direction: Direction,
        state: LifecycleState,
        taxable: String,
        extra: Extra = Extra(),
    ) = SubmissionRow(
        docUuid = uuid,
        version = 1,
        submissionType = SubmissionKind.TYPE_1A,
        state = state,
        dueDate = LocalDate.parse("2026-11-01"),
        documentNumber = "N-$uuid",
        documentTypeCode = "380",
        direction = direction,
        counterpartyName = "Acme Pte Ltd",
        counterpartyUen = "201912345A",
        transactionDate = LocalDate.parse("2026-10-01"),
        taxableAmount = BigDecimal(taxable),
        gstAmount = BigDecimal(extra.gst),
        categoryCodes = extra.categories,
        submittedAt = null,
        acknowledgedAt = if (extra.acked) Instant.EPOCH else null,
        acknowledgementId = if (extra.acked) "A-$uuid" else null,
    )

    private val r1 =
        row("r1", Direction.PURCHASE, LifecycleState.ACKNOWLEDGED, "1000.00", Extra("90.00", setOf("S", "Z"), true))
    private val r2 =
        row("r2", Direction.PURCHASE, LifecycleState.ACKNOWLEDGED, "5000.00", Extra("450.00", acked = true))
    private val r3 =
        row("r3", Direction.PURCHASE, LifecycleState.ACKNOWLEDGED, "2500.00", Extra("225.00", acked = true))
    private val r4 = row("r4", Direction.SALE, LifecycleState.ACKNOWLEDGED, "2500.00", Extra("225.00", acked = true))
    private val r5 = row("r5", Direction.PURCHASE, LifecycleState.QUEUED, "500.00", Extra("45.00"))
    private val r6 = row("r6", Direction.PURCHASE, LifecycleState.SUPERSEDED, "5000.01", Extra("450.00"))
    private val r7 = row("r7", Direction.PURCHASE, LifecycleState.ACKNOWLEDGED, "999.99", Extra("90.00", acked = true))
    private val all = listOf(r1, r2, r3, r4, r5, r6, r7)
    private val none: (SubmissionRow) -> ReconciliationBucket? = { null }

    private fun neverSentExceptR1(row: SubmissionRow): ReconciliationBucket? =
        ReconciliationBucket.NEVER_SENT.takeIf { row.docUuid != "r1" }

    private fun ids(rows: List<SubmissionRow>) = rows.map { it.docUuid }

    private val criterion =
        SubmissionFilter(
            direction = Direction.PURCHASE,
            state = LifecycleState.ACKNOWLEDGED,
            amountMin = BigDecimal("1000.00"),
            amountMax = BigDecimal("5000.00"),
        )

    @Test
    fun `INV-AC-16294 combined filters select rows meeting all three with inclusive bounds`() {
        ids(criterion.select(all, none)) shouldBe listOf("r1", "r2", "r3")
    }

    @Test
    fun `INV-AC-16294 each single filter selects its own subset`() {
        ids(SubmissionFilter(direction = Direction.SALE).select(all, none)) shouldBe listOf("r4")
        ids(SubmissionFilter(state = LifecycleState.QUEUED).select(all, none)) shouldBe listOf("r5")
        ids(SubmissionFilter(submissionType = SubmissionKind.TYPE_2).select(all, none)) shouldBe emptyList()
        ids(SubmissionFilter(amountMin = BigDecimal("5000.00")).select(all, none)) shouldBe listOf("r2", "r6")
        ids(SubmissionFilter(amountMax = BigDecimal("999.99")).select(all, none)) shouldBe listOf("r5", "r7")
    }

    @Test
    fun `INV-AC-16294 totals follow the selection and not the input`() {
        val picked = SubmissionTotals.of(criterion.select(all, none))
        picked shouldBe SubmissionTotals(3, BigDecimal("8500.00"), BigDecimal("765.00"))
        (picked == SubmissionTotals.of(all)) shouldBe false
    }

    @Test
    fun `INV-AC-16294 empty filter selects every row in input order and empty input totals zero`() {
        SubmissionFilter().select(all.reversed(), none) shouldBe all.reversed()
        val zero = SubmissionTotals.of(emptyList())
        zero shouldBe SubmissionTotals(0, BigDecimal("0.00"), BigDecimal("0.00"))
        zero.totalTaxableAmount.scale() shouldBe 2
        zero.totalGstAmount.scale() shouldBe 2
    }

    @Test
    fun `INV-AC-16294 counterparty matches UEN exactly and name ignoring case`() {
        SubmissionFilter(counterparty = "201912345A").matches(r1, none) shouldBe true
        SubmissionFilter(counterparty = "201912345a").matches(r1, none) shouldBe false
        SubmissionFilter(counterparty = "acme pte ltd").matches(r1, none) shouldBe true
        SubmissionFilter(counterparty = "Acme").matches(r1, none) shouldBe false
    }

    @Test
    fun `INV-AC-16294 category code matches membership exactly`() {
        val f = SubmissionFilter(categoryCode = "S")
        f.matches(r1, none) shouldBe true
        f.matches(r2, none) shouldBe false
    }

    @Test
    fun `INV-AC-16294 acknowledged splits rows by acknowledgedAt`() {
        ids(SubmissionFilter(acknowledged = true).select(all, none)) shouldBe listOf("r1", "r2", "r3", "r4", "r7")
        ids(SubmissionFilter(acknowledged = false).select(all, none)) shouldBe listOf("r5", "r6")
    }

    @Test
    fun `INV-AC-16294 bucket filter never matches a null bucket`() {
        val f = SubmissionFilter(bucket = ReconciliationBucket.NEVER_SENT)
        val of = ::neverSentExceptR1
        f.matches(r1, of) shouldBe false
        f.matches(r2, of) shouldBe true
    }

    @Test
    fun `INV-AC-16294 canonical text is order independent and exact`() {
        val a = SubmissionFilter(direction = Direction.PURCHASE, amountMax = BigDecimal("5000.00"))
        val b = SubmissionFilter(amountMax = BigDecimal("5000.00"), direction = Direction.PURCHASE)
        a.canonical() shouldBe b.canonical()
        criterion.canonical() shouldBe "direction=PURCHASE&state=ACKNOWLEDGED&amountMin=1000.00&amountMax=5000.00"
        SubmissionFilter().canonical() shouldBe ""
        val enc = SubmissionFilter(counterparty = "A&B=C").canonical()
        enc shouldBe "counterparty=A%26B%3DC"
        enc.count { it == '&' } shouldBe 0
    }

    @Test
    fun `INV-AC-16294 inconsistent filters throw`() {
        assertThrows<IllegalArgumentException> {
            SubmissionFilter(amountMin = BigDecimal("5.00"), amountMax = BigDecimal("4.00"))
        }
        assertThrows<IllegalArgumentException> { SubmissionFilter(counterparty = " ") }
        assertThrows<IllegalArgumentException> { SubmissionFilter(categoryCode = "") }
        assertThrows<IllegalArgumentException> { SubmissionFilter(amountMin = BigDecimal("1.001")) }
    }

    @Test
    fun `INV-AC-16294 amounts 0_10 and 0_20 total 0_30 exactly`() {
        val rows = listOf(r1.copy(taxableAmount = BigDecimal("0.10")), r2.copy(taxableAmount = BigDecimal("0.20")))
        SubmissionTotals.of(rows).totalTaxableAmount shouldBe BigDecimal("0.30")
    }
}
