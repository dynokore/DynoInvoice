package com.dynokore.gst.evidence.domain.chain

import java.time.LocalDate
import java.util.UUID

/**
 * The entity and period of one document version, learnt from the event that creates it (INV-Q-7213 A).
 * [periodRef] is the payload's `prescribedPeriodRef`, kept opaque.
 */
data class DocumentLocator(
    val docUuid: UUID,
    val version: Int,
    val entityRef: String,
    val periodRef: String,
    val transactionDate: LocalDate,
    val traceReference: String,
    val documentNumber: String?,
) {
    val chain: ChainKey get() = ChainKey(entityRef, periodRef)
}
