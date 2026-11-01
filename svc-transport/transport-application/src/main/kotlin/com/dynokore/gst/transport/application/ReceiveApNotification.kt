package com.dynokore.gst.transport.application

import com.dynokore.gst.transport.domain.AcknowledgementOutcome
import com.dynokore.gst.transport.domain.AdapterId
import com.dynokore.gst.transport.domain.ApNotification
import com.dynokore.gst.transport.domain.DocRef
import com.dynokore.gst.transport.domain.NotificationTranslator
import com.dynokore.gst.transport.domain.Translation

sealed interface ReceiveResult {
    /** Ledger row written now ([recorded] true) or an earlier one found ([recorded] false: a replay). */
    data class Processed(
        val recorded: Boolean,
    ) : ReceiveResult

    /** 400 SRT-001. */
    data class Invalid(
        val reason: String,
    ) : ReceiveResult

    /** No attempt row for the Doc UUID and version: 404 SRT-005. */
    data object OwnerNotFound : ReceiveResult

    /** INBOUND_NETWORK_DOCUMENT: nothing recorded, 503 (INV-Q-9676 A). */
    data object InboundNotSupported : ReceiveResult
}

/**
 * Translates a verified notification and records ledger and event once (INV-REQ-585, INV-Q-9674 A). The sender is
 * verified before this runs (`SenderVerifier`).
 */
class ReceiveApNotification(
    private val owners: AttemptOwner,
    private val ledger: NotificationLedger,
    private val events: AcknowledgementEvents,
    private val unitOfWork: NotificationUnitOfWork,
) {
    fun receive(
        adapter: AdapterId,
        notification: ApNotification,
    ): ReceiveResult =
        when (val t = NotificationTranslator.translate(adapter, notification)) {
            is Translation.Invalid -> {
                ReceiveResult.Invalid(t.reason)
            }

            Translation.InboundNotSupported -> {
                ReceiveResult.InboundNotSupported
            }

            is Translation.LedgerOnly -> {
                val owner = owners.ownerOf(t.doc)
                if (owner == null) {
                    ReceiveResult.OwnerNotFound
                } else {
                    ReceiveResult.Processed(
                        unitOfWork.run(owner) {
                            ledger.record(owner, adapter, t.apNotificationId, LedgerKind.DELIVERY_STATUS) ==
                                LedgerResult.RECORDED
                        },
                    )
                }
            }

            is Translation.Acknowledgement -> {
                acknowledge(adapter, t)
            }
        }

    private fun acknowledge(
        adapter: AdapterId,
        t: Translation.Acknowledgement,
    ): ReceiveResult {
        val owner =
            owners.ownerOf(DocRef(t.event.docUuid, t.event.version))
                ?: return ReceiveResult.OwnerNotFound
        val kind =
            if (t.event.outcome == AcknowledgementOutcome.REJECTED) {
                LedgerKind.REJECTION
            } else {
                LedgerKind.ACKNOWLEDGEMENT
            }
        return ReceiveResult.Processed(
            unitOfWork.run(owner) {
                val recorded = ledger.record(owner, adapter, t.apNotificationId, kind) == LedgerResult.RECORDED
                if (recorded) events.publish(owner, t.event)
                recorded
            },
        )
    }
}
