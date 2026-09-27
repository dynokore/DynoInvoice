package com.dynokore.platform.serviceruntime.binding

/**
 * Outbound mail and webhook delivery (SDS §12.1; INV-REQ-1231 behaviour #1:
 * `notification-svc` is the one owning service for "mail relay and webhook
 * endpoints"). `notification-svc` is this binding's only embedder in practice,
 * but the port is declared here, with the rest of the closed set, rather than in
 * a service-specific module (FR-H.1: the eight bindings are one list).
 *
 * Declared here as one of the closed set of SDS §12.1 bindings (FR-H.1); no
 * implementation ships with this task (done-when, INV-T-2871 / T27) — one lands
 * with `notification-svc`'s own delivery task.
 */
fun interface MessageDelivery : Binding {
    fun send(message: OutboundMessage): DeliveryReceipt
}

/** One outbound message: a channel-agnostic envelope for mail or a webhook call. */
data class OutboundMessage(
    val channel: MessageChannel,
    val to: String,
    val templateId: String,
) {
    init {
        require(to.isNotBlank()) { "to must not be blank" }
        require(templateId.isNotBlank()) { "templateId must not be blank" }
    }
}

enum class MessageChannel { MAIL, WEBHOOK }

/** What [MessageDelivery.send] hands back: not necessarily delivered yet, only accepted for delivery tracking. */
data class DeliveryReceipt(val deliveryId: String) {
    init {
        require(deliveryId.isNotBlank()) { "deliveryId must not be blank" }
    }
}
