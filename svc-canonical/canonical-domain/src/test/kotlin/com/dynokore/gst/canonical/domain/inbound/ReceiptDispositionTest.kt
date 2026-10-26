package com.dynokore.gst.canonical.domain.inbound

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ReceiptDispositionTest {
    private val rejected = NotSubmittable(DispositionReason.BUYER_REJECTED)

    private fun receipt(
        carried: BuyerDisposition,
        previous: BuyerDisposition?,
    ) = ReceiptDisposition.decide(InboundKind.RECEIPT, carried, previous)

    private fun posting(previous: BuyerDisposition?) = ReceiptDisposition.decide(InboundKind.POSTING, null, previous)

    @Test
    fun `INV-AC-621 first receipt PENDING is held`() {
        receipt(BuyerDisposition.PENDING, null) shouldBe HoldPending
    }

    @Test
    fun `INV-AC-618 first receipt ACCEPTED proceeds`() {
        receipt(BuyerDisposition.ACCEPTED, null) shouldBe Proceed
    }

    @Test
    fun `INV-AC-620 first receipt REJECTED is not submittable`() {
        receipt(BuyerDisposition.REJECTED, null) shouldBe
            rejected
    }

    @Test
    fun `INV-AC-621 PENDING after PENDING is held`() {
        receipt(BuyerDisposition.PENDING, BuyerDisposition.PENDING) shouldBe
            HoldPending
    }

    @Test
    fun `INV-AC-621 PENDING after ACCEPTED is held`() {
        receipt(BuyerDisposition.PENDING, BuyerDisposition.ACCEPTED) shouldBe
            HoldPending
    }

    @Test
    fun `INV-AC-621 PENDING after REJECTED is held`() {
        receipt(BuyerDisposition.PENDING, BuyerDisposition.REJECTED) shouldBe
            HoldPending
    }

    @Test
    fun `INV-AC-618 ACCEPTED after PENDING proceeds`() {
        receipt(BuyerDisposition.ACCEPTED, BuyerDisposition.PENDING) shouldBe
            Proceed
    }

    @Test
    fun `INV-AC-621 ACCEPTED after ACCEPTED is held`() {
        receipt(BuyerDisposition.ACCEPTED, BuyerDisposition.ACCEPTED) shouldBe
            HoldPending
    }

    @Test
    fun `INV-AC-618 ACCEPTED after REJECTED proceeds`() {
        receipt(BuyerDisposition.ACCEPTED, BuyerDisposition.REJECTED) shouldBe
            Proceed
    }

    @Test
    fun `INV-AC-620 REJECTED after PENDING is not submittable`() {
        receipt(BuyerDisposition.REJECTED, BuyerDisposition.PENDING) shouldBe rejected
    }

    @Test
    fun `INV-AC-620 REJECTED after ACCEPTED is not submittable`() {
        receipt(BuyerDisposition.REJECTED, BuyerDisposition.ACCEPTED) shouldBe rejected
    }

    @Test
    fun `INV-AC-621 REJECTED after REJECTED is held`() {
        receipt(BuyerDisposition.REJECTED, BuyerDisposition.REJECTED) shouldBe
            HoldPending
    }

    @Test
    fun `INV-AC-621 posting after PENDING is held`() {
        posting(BuyerDisposition.PENDING) shouldBe HoldPending
    }

    @Test
    fun `INV-AC-618 posting after ACCEPTED proceeds`() {
        posting(BuyerDisposition.ACCEPTED) shouldBe Proceed
    }

    @Test
    fun `INV-AC-620 posting after REJECTED is not submittable`() {
        posting(BuyerDisposition.REJECTED) shouldBe rejected
    }

    @Test
    fun `INV-AC-622 posting with no previous proceeds`() {
        posting(null) shouldBe Proceed
    }

    @Test
    fun `INV-AC-10351 INV-AC-10352 INV-AC-10353 the hold code is ING-013`() {
        HoldPending.CODE shouldBe "ING-013"
    }
}
