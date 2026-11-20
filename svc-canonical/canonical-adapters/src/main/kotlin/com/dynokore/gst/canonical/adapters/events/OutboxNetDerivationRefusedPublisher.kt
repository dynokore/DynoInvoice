package com.dynokore.gst.canonical.adapters.events

import com.dynokore.gst.canonical.application.netting.NetCreditNoteRef
import com.dynokore.gst.canonical.application.netting.NetDerivationRefusedPublisher
import com.dynokore.gst.canonical.domain.netting.NetRefusal
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import java.util.UUID

/**
 * Stages `NetDerivationRefused` through the transactional outbox in the caller's transaction (INV-REQ-558 behavior 6).
 * The payload is identifiers and the reason only: the original, the request's event id, the reason and the credit
 * notes the request named. The entity is the one of the request event being answered, supplied by [entity]; the tenant
 * is the session's. The payload `docsequence` of the row is the envelope's, reserved by `appendSequenced`.
 */
class OutboxNetDerivationRefusedPublisher(
    private val outbox: OutboxWriter,
    private val tenants: TenantContext,
    private val entity: () -> EntityRef,
    private val correlation: () -> CorrelationId,
    private val causation: () -> CausationId,
) : NetDerivationRefusedPublisher {
    override fun stage(
        originalDocUuid: UUID,
        requestEventId: UUID,
        reason: NetRefusal,
        creditNotes: List<NetCreditNoteRef>,
    ) {
        outbox.appendSequenced(
            TYPE,
            EventContext(
                tenant = tenants.current(),
                entity = entity(),
                docUuid = DocUuid(originalDocUuid),
                transactionDate = null,
                aggregateKey = originalDocUuid.toString(),
                aggregateVersion = null,
                correlation = correlation(),
                causation = causation(),
            ),
        ) { _ -> payload(originalDocUuid, requestEventId, reason, creditNotes) }
    }

    // Every member is a UUID, an enum name or an integer: none needs JSON escaping.
    private fun payload(
        originalDocUuid: UUID,
        requestEventId: UUID,
        reason: NetRefusal,
        creditNotes: List<NetCreditNoteRef>,
    ): String =
        buildString {
            append("{\"originalDocUuid\":\"").append(originalDocUuid).append('"')
            append(",\"requestEventId\":\"").append(requestEventId).append('"')
            append(",\"reason\":\"").append(reason.name).append('"')
            if (creditNotes.isNotEmpty()) {
                append(",\"creditNotes\":")
                append(
                    creditNotes.joinToString(",", "[", "]") {
                        "{\"docUuid\":\"${it.docUuid}\",\"version\":${it.version}}"
                    },
                )
            }
            append('}')
        }

    companion object {
        const val TYPE = "com.dynokore.gst.canonical.NetDerivationRefused.v1"
    }
}
