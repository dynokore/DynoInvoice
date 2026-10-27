package com.dynokore.gst.canonical.domain.dating

import com.dynokore.gst.canonical.domain.dating.DatingDecision.Dated
import com.dynokore.gst.canonical.domain.dating.DatingDecision.Held
import com.dynokore.gst.canonical.domain.dating.DatingDecision.PostingTimestampRequired
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/** Step 2 of the derivation order (INV-REQ-67, INV-REQ-68; INV-Q-6933 A). */
class TransactionDatingTest {
    private val invoiceDate = LocalDate.of(2026, 3, 28)
    private val postedApr2 = PostingMoment.Zoned(OffsetDateTime.parse("2026-04-02T10:00+08:00"))
    private val newYork = ZoneId.of("America/New_York")

    private fun input(
        direction: Direction = Direction.PURCHASE,
        invoiceBacked: Boolean = true,
        posting: PostingMoment? = postedApr2,
        sourceZone: ZoneId = SINGAPORE,
        election: DateBasis? = null,
    ) = DatingInput(direction, invoiceBacked, invoiceDate, posting, sourceZone, election)

    private fun decide(
        direction: Direction = Direction.PURCHASE,
        invoiceBacked: Boolean = true,
        posting: PostingMoment? = postedApr2,
        sourceZone: ZoneId = SINGAPORE,
        election: DateBasis? = null,
    ) = TransactionDating.decide(input(direction, invoiceBacked, posting, sourceZone, election))

    @Test
    fun `INV-AC-108 posting date basis dates a purchase by its posting date`() {
        decide(election = DateBasis.POSTING_DATE) shouldBe
            Dated(LocalDate.of(2026, 4, 2), DateBasis.POSTING_DATE, null)
    }

    @Test
    fun `INV-AC-138 invoice date basis dates a purchase by the supplier invoice date`() {
        decide(election = DateBasis.INVOICE_DATE) shouldBe Dated(invoiceDate, DateBasis.INVOICE_DATE, null)
    }

    @Test
    fun `INV-AC-195 a purchase before the election is held with PER-011 and released on election`() {
        decide(election = null) shouldBe Held(HoldCode.PER_011)
        decide(election = null, posting = null) shouldBe Held(HoldCode.PER_011)
        decide(election = DateBasis.INVOICE_DATE) shouldBe Dated(invoiceDate, DateBasis.INVOICE_DATE, null)
        HoldCode.PER_011.code shouldBe "PER-011"
    }

    @Test
    fun `INV-AC-2269 a purchase held before the election is released and dated by its invoice date`() {
        decide(election = null) shouldBe Held(HoldCode.PER_011)
        decide(election = DateBasis.INVOICE_DATE) shouldBe Dated(invoiceDate, DateBasis.INVOICE_DATE, null)
    }

    @Test
    fun `INV-AC-10589 a sale with no election is dated and not held`() {
        decide(direction = Direction.SALE, election = null) shouldBe Dated(invoiceDate, DateBasis.INVOICE_DATE, null)
    }

    @Test
    fun `INV-AC-10589 a sale keeps the document date whatever the election and the posting`() {
        decide(direction = Direction.SALE, election = DateBasis.POSTING_DATE) shouldBe
            Dated(invoiceDate, DateBasis.INVOICE_DATE, null)
        decide(direction = Direction.SALE, election = DateBasis.POSTING_DATE, posting = null) shouldBe
            Dated(invoiceDate, DateBasis.INVOICE_DATE, null)
    }

    @Test
    fun `INV-AC-10589 a purchase not invoice backed takes the posting date and is not held`() {
        decide(invoiceBacked = false, election = null) shouldBe
            Dated(LocalDate.of(2026, 4, 2), DateBasis.POSTING_DATE, null)
        decide(invoiceBacked = false, election = DateBasis.INVOICE_DATE) shouldBe
            Dated(LocalDate.of(2026, 4, 2), DateBasis.POSTING_DATE, null)
    }

    @Test
    fun `INV-AC-10589 a sale not invoice backed takes the posting date`() {
        decide(direction = Direction.SALE, invoiceBacked = false, election = null) shouldBe
            Dated(LocalDate.of(2026, 4, 2), DateBasis.POSTING_DATE, null)
    }

    @Test
    fun `INV-AC-10589 a missing posting timestamp is required when the posting date decides`() {
        decide(election = DateBasis.POSTING_DATE, posting = null) shouldBe PostingTimestampRequired
        decide(direction = Direction.SALE, invoiceBacked = false, posting = null) shouldBe PostingTimestampRequired
        decide(invoiceBacked = false, posting = null, election = DateBasis.INVOICE_DATE) shouldBe
            PostingTimestampRequired
        PostingTimestampRequired.refusalCode shouldBe "SRT-001"
    }

    @Test
    fun `INV-AC-10589 a missing posting timestamp is no problem for a backed sale or the invoice date election`() {
        decide(direction = Direction.SALE, posting = null) shouldBe Dated(invoiceDate, DateBasis.INVOICE_DATE, null)
        decide(election = DateBasis.INVOICE_DATE, posting = null) shouldBe
            Dated(invoiceDate, DateBasis.INVOICE_DATE, null)
    }

    @Test
    fun `INV-AC-10589 the posting date is read in Singapore time at the day boundary`() {
        fun at(utc: String) =
            decide(election = DateBasis.POSTING_DATE, posting = PostingMoment.Zoned(OffsetDateTime.parse(utc)))
        at("2026-03-31T16:00:00Z") shouldBe Dated(LocalDate.of(2026, 4, 1), DateBasis.POSTING_DATE, null)
        at("2026-03-31T15:59:59Z") shouldBe Dated(LocalDate.of(2026, 3, 31), DateBasis.POSTING_DATE, null)
        at("2026-03-31T23:30-04:00") shouldBe Dated(LocalDate.of(2026, 4, 1), DateBasis.POSTING_DATE, null)
    }

    @Test
    fun `INV-AC-10589 a zoneless posting is read in the source zone and reports the assumed zone`() {
        val moment = PostingMoment.Zoneless(LocalDateTime.parse("2026-03-31T23:30"))
        decide(election = DateBasis.POSTING_DATE, posting = moment, sourceZone = newYork) shouldBe
            Dated(LocalDate.of(2026, 4, 1), DateBasis.POSTING_DATE, newYork)
        decide(election = DateBasis.POSTING_DATE, posting = moment, sourceZone = SINGAPORE) shouldBe
            Dated(LocalDate.of(2026, 3, 31), DateBasis.POSTING_DATE, SINGAPORE)
    }

    @Test
    fun `INV-AC-10589 equal input gives equal output`() {
        decide(election = DateBasis.POSTING_DATE) shouldBe decide(election = DateBasis.POSTING_DATE)
    }
}
