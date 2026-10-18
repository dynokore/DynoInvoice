package com.dynokore.platform.serviceruntime.binding

/**
 * Outbound message delivery (INV-REQ-817; owner ruling INV-Q-6751 option A): one implementation per
 * [MessageChannel], each covering send, delivery status and bounce or failure handling. A service holding
 * several implementations selects one by [channel]; with no SMS gateway bound it finds none for
 * [MessageChannel.SMS] and sends the passcode by a second e-mail (INV-REQ-817 behavior 4).
 * `notification-svc` is the only caller in practice (INV-ADR-279), but the port is declared here with the
 * rest of the closed binding set (FR-H.1).
 *
 * No implementation ships in this module: the SaaS mail service and SMS gateway, and the private SMTP relay
 * and mail API, are the owning service's adapters.
 */
interface MessageDelivery : Binding {
    /** The one channel this implementation serves. */
    val channel: MessageChannel

    /** Accepts [message] for delivery; the receipt is not a delivery, only the id to ask [status] about. */
    fun send(message: OutboundMessage): DeliveryReceipt

    /** The current state of a delivery this implementation accepted; a bounce or failure carries its reason. */
    fun status(deliveryId: String): DeliveryStatus
}

/** The delivery channels; the SMS channel carries signed-link passcodes only (INV-REQ-2762). */
enum class MessageChannel { EMAIL, SMS }

/** What an [OutboundMessage] carries: a reference to a signed link, or a sealed passcode. Never free text. */
sealed interface MessageContent

/** A reference to a signed link; e-mail only. */
data class LinkContent(
    val linkReference: String,
) : MessageContent {
    init {
        require(linkReference.isNotBlank()) { "linkReference must not be blank" }
    }
}

/** A sealed signed-link passcode; carried by SMS, or by a second e-mail when no SMS gateway is bound. */
data class PasscodeContent(
    val sealedPasscode: String,
) : MessageContent {
    init {
        require(sealedPasscode.isNotBlank()) { "sealedPasscode must not be blank" }
    }
}

/** One outbound message: a template reference and its content, never a free-text body. */
data class OutboundMessage(
    val channel: MessageChannel,
    val to: String,
    val templateId: String,
    val templateVersion: String,
    val content: MessageContent,
) {
    init {
        require(to.isNotBlank()) { "to must not be blank" }
        require(templateId.isNotBlank()) { "templateId must not be blank" }
        require(templateVersion.isNotBlank()) { "templateVersion must not be blank" }
        require(channel != MessageChannel.SMS || content is PasscodeContent) {
            "the SMS channel carries passcodes only (INV-REQ-2762)"
        }
    }
}

/** What [MessageDelivery.send] hands back: not necessarily delivered yet, only accepted for delivery tracking. */
data class DeliveryReceipt(
    val deliveryId: String,
) {
    init {
        require(deliveryId.isNotBlank()) { "deliveryId must not be blank" }
    }
}

enum class DeliveryState { QUEUED, SENT, DELIVERED, BOUNCED, FAILED }

/** The state of one delivery; [reason] is present exactly in [DeliveryState.BOUNCED] and [DeliveryState.FAILED]. */
data class DeliveryStatus(
    val deliveryId: String,
    val state: DeliveryState,
    val reason: String? = null,
) {
    init {
        require(deliveryId.isNotBlank()) { "deliveryId must not be blank" }
        if (state == DeliveryState.BOUNCED || state == DeliveryState.FAILED) {
            require(!reason.isNullOrBlank()) { "reason must not be blank in state $state" }
        } else {
            require(reason == null) { "reason must be null in state $state" }
        }
    }
}
