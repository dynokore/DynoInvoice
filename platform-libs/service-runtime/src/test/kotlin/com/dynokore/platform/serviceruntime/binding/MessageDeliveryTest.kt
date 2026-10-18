package com.dynokore.platform.serviceruntime.binding

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MessageDeliveryTest {
    private val link = LinkContent("link-ref-1")
    private val passcode = PasscodeContent("sealed-1")

    private fun message(
        channel: MessageChannel = MessageChannel.EMAIL,
        to: String = "a@example.test",
        templateId: String = "invite",
        templateVersion: String = "1",
        content: MessageContent = link,
    ) = OutboundMessage(channel, to, templateId, templateVersion, content)

    private class StubDelivery(
        override val channel: MessageChannel,
    ) : MessageDelivery {
        private val sent = mutableMapOf<String, OutboundMessage>()

        override fun send(message: OutboundMessage): DeliveryReceipt {
            val id = "d-${sent.size + 1}"
            sent[id] = message
            return DeliveryReceipt(id)
        }

        override fun status(deliveryId: String): DeliveryStatus {
            require(deliveryId in sent) { "unknown delivery" }
            return DeliveryStatus(deliveryId, DeliveryState.BOUNCED, "mailbox unknown")
        }
    }

    @Test
    fun `INV-AC-841 a bounce is recorded against its delivery and a service with no SMS binding finds none`() {
        val bindings: List<MessageDelivery> = listOf(StubDelivery(MessageChannel.EMAIL))
        val email = bindings.single { it.channel == MessageChannel.EMAIL }
        val receipt = email.send(message(content = link))
        val status = email.status(receipt.deliveryId)
        status.deliveryId shouldBe receipt.deliveryId
        status.state shouldBe DeliveryState.BOUNCED
        status.reason shouldBe "mailbox unknown"
        bindings.filter { it.channel == MessageChannel.SMS }.shouldBeEmpty()
    }

    @Test
    fun `INV-REQ-817 channel and delivery state names are exactly the ruled sets`() {
        MessageChannel.entries.map { it.name }.toSet() shouldBe setOf("EMAIL", "SMS")
        DeliveryState.entries.map { it.name }.toSet() shouldBe
            setOf("QUEUED", "SENT", "DELIVERED", "BOUNCED", "FAILED")
    }

    @Test
    fun `INV-REQ-817 OutboundMessage declares exactly the ruled fields and no free-text body`() {
        OutboundMessage::class.java.declaredFields
            .filter { !it.isSynthetic }
            .map { it.name } shouldBe listOf("channel", "to", "templateId", "templateVersion", "content")
    }

    @Test
    fun `INV-REQ-817 SMS accepts a passcode`() {
        message(channel = MessageChannel.SMS, content = passcode).content shouldBe passcode
    }

    @Test
    fun `INV-REQ-817 SMS refuses link content`() {
        shouldThrow<IllegalArgumentException> { message(channel = MessageChannel.SMS, content = link) }
    }

    @Test
    fun `INV-REQ-817 EMAIL accepts both contents`() {
        message(content = link).content shouldBe link
        message(content = passcode).content shouldBe passcode
    }

    @Test
    fun `blank fields are refused`() {
        for (blank in listOf("", "  ")) {
            shouldThrow<IllegalArgumentException> { message(to = blank) }
            shouldThrow<IllegalArgumentException> { message(templateId = blank) }
            shouldThrow<IllegalArgumentException> { message(templateVersion = blank) }
            shouldThrow<IllegalArgumentException> { LinkContent(blank) }
            shouldThrow<IllegalArgumentException> { PasscodeContent(blank) }
            shouldThrow<IllegalArgumentException> { DeliveryReceipt(blank) }
            shouldThrow<IllegalArgumentException> { DeliveryStatus(blank, DeliveryState.SENT) }
        }
    }

    @Test
    fun `a templateVersion with a leading space is not blank`() {
        message(templateVersion = " 1").templateVersion shouldBe " 1"
        shouldThrow<IllegalArgumentException> { message(templateVersion = "") }
    }

    @Test
    fun `DeliveryStatus reason is required in BOUNCED and FAILED and absent otherwise`() {
        for (state in DeliveryState.entries) {
            val needsReason = state == DeliveryState.BOUNCED || state == DeliveryState.FAILED
            if (needsReason) {
                DeliveryStatus("d", state, "why").reason shouldBe "why"
                shouldThrow<IllegalArgumentException> { DeliveryStatus("d", state, null) }
                shouldThrow<IllegalArgumentException> { DeliveryStatus("d", state, "") }
                shouldThrow<IllegalArgumentException> { DeliveryStatus("d", state, "  ") }
            } else {
                DeliveryStatus("d", state, null).reason shouldBe null
                shouldThrow<IllegalArgumentException> { DeliveryStatus("d", state, "why") }
            }
        }
    }
}
