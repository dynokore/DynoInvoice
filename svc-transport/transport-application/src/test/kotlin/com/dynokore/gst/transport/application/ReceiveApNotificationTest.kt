package com.dynokore.gst.transport.application

import com.dynokore.gst.transport.domain.AdapterId
import com.dynokore.gst.transport.domain.ApNotification
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID

class ReceiveApNotificationTest {
    private val ledger = CountingLedger()
    private val events = CountingEvents()
    private val useCase = ReceiveApNotification(FakeOwners(), ledger, events, DirectUnitOfWork())
    private val one = AdapterId("peppol-ap-1")
    private val two = AdapterId("peppol-ap-2")
    private val ack = ApNotification("APN-1", "ACKNOWLEDGEMENT", KNOWN_DOC, 1, "ACK-1")

    @Test
    fun `a valid acknowledgement records the ledger and the event once`() {
        useCase.receive(one, ack) shouldBe ReceiveResult.Processed(true)
        ledger.calls shouldBe 1
        events.published.size shouldBe 1
    }

    @Test
    fun `a replay returns recorded false and publishes once in all`() {
        useCase.receive(one, ack)
        useCase.receive(one, ack) shouldBe ReceiveResult.Processed(false)
        events.published.size shouldBe 1
    }

    @Test
    fun `the same notification id on two adapters records twice`() {
        useCase.receive(one, ack)
        useCase.receive(two, ack)
        ledger.rows.size shouldBe 2
        events.published.size shouldBe 2
    }

    @Test
    fun `an unknown doc uuid is not found with zero ledger calls and zero publishes`() {
        useCase.receive(one, ack.copy(docUuid = UUID.randomUUID())) shouldBe ReceiveResult.OwnerNotFound
        ledger.calls shouldBe 0
        events.published.size shouldBe 0
    }

    @Test
    fun `an unknown version is not found with zero ledger calls and zero publishes`() {
        useCase.receive(one, ack.copy(version = 7)) shouldBe ReceiveResult.OwnerNotFound
        ledger.calls shouldBe 0
        events.published.size shouldBe 0
    }

    @Test
    fun `a delivery status gives one ledger record and zero publishes`() {
        useCase.receive(one, ApNotification("APN-3", "DELIVERY_STATUS", KNOWN_DOC, 1)) shouldBe ReceiveResult.Processed(true)
        ledger.calls shouldBe 1
        events.published.size shouldBe 0
    }

    @Test
    fun `an inbound network document twice gives zero records`() {
        val inbound = ApNotification("APN-4", "INBOUND_NETWORK_DOCUMENT")
        repeat(2) { useCase.receive(one, inbound) shouldBe ReceiveResult.InboundNotSupported }
        ledger.calls shouldBe 0
        events.published.size shouldBe 0
    }

    @Test
    fun `an invalid notification touches no port`() {
        val unknownKind = ApNotification("APN-5", "IRAS_REJECTION", KNOWN_DOC, 1)
        useCase.receive(one, unknownKind).shouldBeInstanceOf<ReceiveResult.Invalid>()
        ledger.calls shouldBe 0
        events.published.size shouldBe 0
    }
}
