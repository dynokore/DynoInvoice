package com.dynokore.gst.submission.domain.register

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

class ProvisionalTransactionDateTest {
    private val issued = LocalDate.of(2026, 6, 20)
    private val posted = LocalDate.of(2026, 7, 2)

    private fun select(
        direction: DocumentDirection,
        flag: Boolean?,
        election: DateElection = DateElection.Unknown,
        posting: LocalDate? = posted,
    ) = ProvisionalTransactionDate.select(direction, flag, election, issued, posting)

    @Test
    fun `INV-AC-20689 a sale with an invoice takes the issuance date`() {
        select(DocumentDirection.SALE, true) shouldBe issued
    }

    @Test
    fun `INV-AC-20689 a sale without an invoice takes the posting date`() {
        select(DocumentDirection.SALE, false) shouldBe posted
    }

    @Test
    fun `INV-AC-20689 a purchase with an invoice follows the election`() {
        select(DocumentDirection.PURCHASE, true, DateElection.Issuance) shouldBe issued
        select(DocumentDirection.PURCHASE, true, DateElection.Posting) shouldBe posted
        select(DocumentDirection.PURCHASE, true, DateElection.Unknown) shouldBe issued
    }

    @Test
    fun `INV-AC-20689 a purchase without an invoice takes the posting date`() {
        select(DocumentDirection.PURCHASE, false) shouldBe posted
    }

    @Test
    fun `INV-AC-20689 an unknown case takes the earliest candidate date`() {
        select(DocumentDirection.SALE, null) shouldBe issued
        select(DocumentDirection.SALE, null, posting = LocalDate.of(2026, 6, 1)) shouldBe LocalDate.of(2026, 6, 1)
    }

    @Test
    fun `INV-AC-20689 a missing posting date gives the earliest known candidate`() {
        select(DocumentDirection.SALE, false, posting = null) shouldBe issued
    }

    @Test
    fun `a sale with a false flag returns the posting date even when later than issuance`() {
        select(DocumentDirection.SALE, false) shouldBe posted
    }

    @Test
    fun `the election Posting with no posting date returns the issuance date`() {
        select(DocumentDirection.PURCHASE, true, DateElection.Posting, posting = null) shouldBe issued
    }
}
