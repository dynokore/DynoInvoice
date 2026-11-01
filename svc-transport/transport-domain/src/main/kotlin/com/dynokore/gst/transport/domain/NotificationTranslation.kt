package com.dynokore.gst.transport.domain

import java.util.UUID

/** The tenant an attempt row belongs to (the owner of a Doc UUID, INV-Q-9674). */
@JvmInline
value class TenantRef(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "a tenant ref is not blank" }
    }

    override fun toString(): String = value
}

/** One reason of a rejection notification, in the order the AP sent it. */
data class NotificationReason(
    val code: String?,
    val message: String?,
)

/** An AP notification as it arrives (callback body or polled item), before any rule: every field may be absent. */
data class ApNotification(
    val apNotificationId: String?,
    val kind: String?,
    val docUuid: UUID? = null,
    val version: Int? = null,
    val acknowledgementId: String? = null,
    val reasons: List<NotificationReason>? = null,
)

enum class AcknowledgementOutcome { ACCEPTED, REJECTED }

data class AckReason(
    val code: String,
    val message: String,
)

/** The `AcknowledgementReceived` model (events catalogue, INV-Q-9672 B). `reasons` is empty unless REJECTED. */
data class AcknowledgementReceived(
    val adapter: AdapterId,
    val apNotificationId: String,
    val docUuid: UUID,
    val version: Int,
    val acknowledgementId: String,
    val outcome: AcknowledgementOutcome,
    val reasons: List<AckReason> = emptyList(),
) {
    val doc: DocRef get() = DocRef(docUuid, version)
}

sealed interface Translation {
    /** The notification breaks a rule of the contract: the endpoint answers 400 SRT-001. */
    data class Invalid(
        val reason: String,
    ) : Translation

    /** Ledger row and one `AcknowledgementReceived` event. */
    data class Acknowledgement(
        val apNotificationId: String,
        val event: AcknowledgementReceived,
    ) : Translation

    /** Ledger row only, no event (interim: INV-Q-9774 is open). */
    data class LedgerOnly(
        val apNotificationId: String,
        val doc: DocRef,
    ) : Translation

    /** An inbound network document is refused and records nothing (INV-Q-9676 A, the endpoint answers 503). */
    data object InboundNotSupported : Translation
}

/** Translation of an AP notification into the internal model (INV-REQ-585 behaviour 4); pure. */
object NotificationTranslator {
    const val ACKNOWLEDGEMENT = "ACKNOWLEDGEMENT"
    const val DELIVERY_STATUS = "DELIVERY_STATUS"
    const val REJECTION = "REJECTION"
    const val INBOUND_NETWORK_DOCUMENT = "INBOUND_NETWORK_DOCUMENT"
    private const val MAX_ID = 100

    fun translate(
        adapter: AdapterId,
        n: ApNotification,
    ): Translation {
        val id = n.apNotificationId
        return when {
            id.isNullOrBlank() || id.length > MAX_ID -> {
                Translation.Invalid("apNotificationId is 1 to $MAX_ID characters")
            }

            else -> {
                byKind(adapter, id, n)
            }
        }
    }

    private fun byKind(
        adapter: AdapterId,
        id: String,
        n: ApNotification,
    ): Translation =
        when (n.kind) {
            ACKNOWLEDGEMENT -> acknowledgement(adapter, id, n)
            REJECTION -> rejection(adapter, id, n)
            DELIVERY_STATUS -> deliveryStatus(id, n)
            INBOUND_NETWORK_DOCUMENT -> Translation.InboundNotSupported
            else -> Translation.Invalid("kind '${n.kind}' is not one of the four kinds")
        }

    private fun deliveryStatus(
        id: String,
        n: ApNotification,
    ): Translation {
        val doc = n.docUuid
        val version = n.version
        return when {
            doc == null -> {
                Translation.Invalid("a DELIVERY_STATUS needs docUuid")
            }

            version == null || version < 1 -> {
                Translation.Invalid("a DELIVERY_STATUS needs version of 1 or more")
            }

            else -> {
                Translation.LedgerOnly(id, DocRef(doc, version))
            }
        }
    }

    private fun acknowledgement(
        adapter: AdapterId,
        id: String,
        n: ApNotification,
    ): Translation {
        val ack = n.acknowledgementId
        val doc = n.docUuid
        val version = n.version
        return when {
            ack.isNullOrBlank() -> {
                Translation.Invalid("an ACKNOWLEDGEMENT needs acknowledgementId")
            }

            doc == null -> {
                Translation.Invalid("an ACKNOWLEDGEMENT needs docUuid")
            }

            version == null || version < 1 -> {
                Translation.Invalid("an ACKNOWLEDGEMENT needs version of 1 or more")
            }

            else -> {
                Translation.Acknowledgement(
                    id,
                    AcknowledgementReceived(adapter, id, doc, version, ack, AcknowledgementOutcome.ACCEPTED),
                )
            }
        }
    }

    private fun rejection(
        adapter: AdapterId,
        id: String,
        n: ApNotification,
    ): Translation {
        val doc = n.docUuid
        val version = n.version
        val reasons = n.reasons
        return when {
            doc == null -> {
                Translation.Invalid("a REJECTION needs docUuid")
            }

            version == null || version < 1 -> {
                Translation.Invalid("a REJECTION needs version of 1 or more")
            }

            reasons.isNullOrEmpty() -> {
                Translation.Invalid("a REJECTION needs reasons")
            }

            reasons.any { it.code.isNullOrBlank() || it.message.isNullOrBlank() } -> {
                Translation.Invalid("each reason needs code and message")
            }

            // INV-Q-9772 A: the acknowledgementId of a rejection is the notification id of the rejection
            else -> {
                Translation.Acknowledgement(
                    id,
                    AcknowledgementReceived(
                        adapter,
                        id,
                        doc,
                        version,
                        id,
                        AcknowledgementOutcome.REJECTED,
                        reasons.map { AckReason(it.code.orEmpty(), it.message.orEmpty()) },
                    ),
                )
            }
        }
    }
}
