package com.dynokore.gst.transport.domain

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID

class NotificationTranslatorTest {
    private val adapter = AdapterId("peppol-ap-1")
    private val doc = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6")

    private fun tr(n: ApNotification) = NotificationTranslator.translate(adapter, n)

    private fun invalid(n: ApNotification) {
        tr(n).shouldBeInstanceOf<Translation.Invalid>()
    }

    private val ack = ApNotification("APN-1", "ACKNOWLEDGEMENT", doc, 1, "ACK-1")
    private val rej =
        ApNotification(
            "APN-2",
            "REJECTION",
            doc,
            2,
            reasons =
                listOf(
                    NotificationReason("PEPPOL-R001", "Buyer endpoint unknown"),
                    NotificationReason("PEPPOL-R002", "Schematron failure"),
                ),
        )

    @Test
    fun `a kind outside the four is invalid`() {
        invalid(ApNotification("APN-9", "IRAS_REJECTION", doc, 1))
        invalid(ApNotification("APN-9", "acknowledgement", doc, 1, "A"))
        invalid(ApNotification("APN-9", null))
    }

    @Test
    fun `an acknowledgement without acknowledgementId is invalid`() = invalid(ack.copy(acknowledgementId = null))

    @Test
    fun `an acknowledgement without docUuid is invalid`() = invalid(ack.copy(docUuid = null))

    @Test
    fun `an acknowledgement without version is invalid`() {
        invalid(ack.copy(version = null))
        invalid(ack.copy(version = 0))
    }

    @Test
    fun `a rejection without docUuid is invalid`() = invalid(rej.copy(docUuid = null))

    @Test
    fun `a rejection without version is invalid`() = invalid(rej.copy(version = null))

    @Test
    fun `a rejection without reasons is invalid`() {
        invalid(rej.copy(reasons = null))
        invalid(rej.copy(reasons = emptyList()))
        invalid(rej.copy(reasons = listOf(NotificationReason("PEPPOL-R001", null))))
    }

    @Test
    fun `an acknowledgement translates to an accepted AcknowledgementReceived without reasons`() {
        tr(ack) shouldBe
            Translation.Acknowledgement(
                "APN-1",
                AcknowledgementReceived(adapter, "APN-1", doc, 1, "ACK-1", AcknowledgementOutcome.ACCEPTED),
            )
    }

    @Test
    fun `a rejection keeps its two reasons in order and uses the notification id as acknowledgementId`() {
        val e = (tr(rej) as Translation.Acknowledgement).event
        e.outcome shouldBe AcknowledgementOutcome.REJECTED
        e.acknowledgementId shouldBe "APN-2"
        e.apNotificationId shouldBe "APN-2"
        e.version shouldBe 2
        e.reasons shouldBe
            listOf(AckReason("PEPPOL-R001", "Buyer endpoint unknown"), AckReason("PEPPOL-R002", "Schematron failure"))
    }

    @Test
    fun `delivery status is ledger only`() =
        tr(ApNotification("APN-3", "DELIVERY_STATUS", doc, 1)) shouldBe Translation.LedgerOnly("APN-3", DocRef(doc, 1))

    @Test
    fun `an inbound network document is refused`() =
        tr(ApNotification("APN-4", "INBOUND_NETWORK_DOCUMENT")) shouldBe Translation.InboundNotSupported

    @Test
    fun `a missing or overlong notification id is invalid`() {
        invalid(ack.copy(apNotificationId = null))
        invalid(ack.copy(apNotificationId = " "))
        invalid(ack.copy(apNotificationId = "x".repeat(101)))
    }

    @Test
    fun `INV-AC-623 a complete delivery status gives ledger only with the same doc uuid and version`() {
        val t = tr(ApNotification("APN-3", "DELIVERY_STATUS", doc, 4)).shouldBeInstanceOf<Translation.LedgerOnly>()
        t.doc.docUuid shouldBe doc
        t.doc.version shouldBe 4
    }

    @Test
    fun `a delivery status without docUuid is invalid`() =
        tr(ApNotification("APN-3", "DELIVERY_STATUS", null, 1)) shouldBe
            Translation.Invalid("a DELIVERY_STATUS needs docUuid")

    @Test
    fun `a delivery status without version is invalid`() =
        tr(ApNotification("APN-3", "DELIVERY_STATUS", doc, null)) shouldBe
            Translation.Invalid("a DELIVERY_STATUS needs version of 1 or more")

    @Test
    fun `a delivery status with version 0 is invalid`() =
        tr(ApNotification("APN-3", "DELIVERY_STATUS", doc, 0)) shouldBe
            Translation.Invalid("a DELIVERY_STATUS needs version of 1 or more")

    @Test
    fun `a delivery status with a blank notification id is invalid by the id rule`() =
        tr(ApNotification(" ", "DELIVERY_STATUS", doc, 1)) shouldBe
            Translation.Invalid("apNotificationId is 1 to 100 characters")
}
