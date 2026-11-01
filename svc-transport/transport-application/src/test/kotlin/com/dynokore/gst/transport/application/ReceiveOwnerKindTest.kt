package com.dynokore.gst.transport.application

import com.dynokore.gst.transport.domain.AdapterId
import com.dynokore.gst.transport.domain.ApNotification
import com.dynokore.gst.transport.domain.NotificationReason
import com.dynokore.gst.transport.domain.NotificationTranslator
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID

/** Owner and ledger kind reach the unit of work and the ledger (INV-Q-14951 A; INV-AC-623, INV-AC-9488). */
class ReceiveOwnerKindTest {
    private val ledger = CountingLedger()
    private val events = CountingEvents()
    private val uow = DirectUnitOfWork()
    private val owners = CountingOwners()
    private val useCase = ReceiveApNotification(owners, ledger, events, uow)
    private val one = AdapterId("peppol-ap-1")
    private val two = AdapterId("peppol-ap-2")
    private val ack = ApNotification("APN-1", "ACKNOWLEDGEMENT", KNOWN_DOC, 1, "ACK-1")
    private val delivery = ApNotification("APN-3", "DELIVERY_STATUS", KNOWN_DOC, 1)

    private class CountingOwners : AttemptOwner {
        var lookups = 0
        private val inner = FakeOwners()

        override fun ownerOf(doc: com.dynokore.gst.transport.domain.DocRef): AttemptOwnerRef? {
            lookups++
            return inner.ownerOf(doc)
        }
    }

    @Test
    fun `INV-AC-623 an acknowledgement passes OWNER to unit of work, ledger and publisher`() {
        useCase.receive(one, ack) shouldBe ReceiveResult.Processed(true)
        uow.owners shouldBe listOf(OWNER)
        ledger.owners shouldBe listOf(OWNER)
        ledger.kinds shouldBe listOf(LedgerKind.ACKNOWLEDGEMENT)
        events.publishOwners shouldBe listOf(OWNER)
    }

    @Test
    fun `a rejection records the ledger kind REJECTION`() {
        val rej = ApNotification("APN-2", "REJECTION", KNOWN_DOC, 1, reasons = listOf(NotificationReason("R1", "m")))
        useCase.receive(one, rej) shouldBe ReceiveResult.Processed(true)
        ledger.kinds shouldBe listOf(LedgerKind.REJECTION)
        ledger.owners shouldBe listOf(OWNER)
        uow.owners shouldBe listOf(OWNER)
        events.publishOwners shouldBe listOf(OWNER)
    }

    @Test
    fun `INV-AC-623 a delivery status for the known doc uuid records with OWNER and publishes nothing`() {
        useCase.receive(one, delivery) shouldBe ReceiveResult.Processed(true)
        uow.owners shouldBe listOf(OWNER)
        ledger.owners shouldBe listOf(OWNER)
        ledger.kinds shouldBe listOf(LedgerKind.DELIVERY_STATUS)
        events.published.size shouldBe 0
    }

    @Test
    fun `a delivery status for an unknown doc uuid is not found with zero unit of work, ledger and publish calls`() {
        useCase.receive(one, delivery.copy(docUuid = UUID.randomUUID())) shouldBe ReceiveResult.OwnerNotFound
        uow.owners.size shouldBe 0
        ledger.calls shouldBe 0
        events.published.size shouldBe 0
    }

    @Test
    fun `a delivery status without docUuid is invalid with zero owner lookups, ledger and publish calls`() {
        useCase.receive(one, delivery.copy(docUuid = null)).shouldBeInstanceOf<ReceiveResult.Invalid>()
        owners.lookups shouldBe 0
        uow.owners.size shouldBe 0
        ledger.calls shouldBe 0
        events.published.size shouldBe 0
    }

    @Test
    fun `INV-AC-623 a replay gives Processed false and zero further publishes`() {
        useCase.receive(one, ack)
        useCase.receive(one, ack) shouldBe ReceiveResult.Processed(false)
        events.published.size shouldBe 1
    }

    @Test
    fun `the same notification id on two adapters records twice`() {
        useCase.receive(one, delivery)
        useCase.receive(two, delivery)
        ledger.rows.size shouldBe 2
    }

    @Test
    fun `a publisher that throws makes the unit of work propagate the failure`() {
        val failing =
            object : AcknowledgementEvents {
                override fun publish(
                    owner: AttemptOwnerRef,
                    event: com.dynokore.gst.transport.domain.AcknowledgementReceived,
                ) = error("publish failed")
            }
        val failingCase = ReceiveApNotification(owners, ledger, failing, uow)
        shouldThrow<IllegalStateException> { failingCase.receive(one, ack) }
    }

    @Test
    fun `the ledger kinds are exactly the three recordable kinds named as the translator constants`() {
        LedgerKind.entries.map { it.name } shouldBe
            listOf(
                NotificationTranslator.ACKNOWLEDGEMENT,
                NotificationTranslator.DELIVERY_STATUS,
                NotificationTranslator.REJECTION,
            )
    }
}
