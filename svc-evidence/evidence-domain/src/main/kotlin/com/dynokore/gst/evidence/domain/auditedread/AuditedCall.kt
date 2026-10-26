package com.dynokore.gst.evidence.domain.auditedread

import java.util.UUID

/** One audited read that passed validation and authorisation (INV-Q-7207 A item 3). */
sealed interface AuditedCall {
    data class AcknowledgementChain(
        val ackId: String,
    ) : AuditedCall

    data class ReverseLookup(
        val parameters: Map<ReverseLookupParameter, String>,
    ) : AuditedCall

    data class AuditPackCreate(
        val entityRef: String,
        val periodRef: String,
    ) : AuditedCall

    data class AuditPackRead(
        val entityRef: String,
        val periodRef: String,
        val packId: UUID,
    ) : AuditedCall
}

/** The action and scope of an audit entry; strings only, the domain builds no `AuditEntry` and holds no time. */
data class AuditedReadEntry(
    val action: String,
    val scope: String,
)
