package com.dynokore.gst.canonical.application.document

import com.dynokore.gst.canonical.domain.derivation.GateDecision
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.util.UUID

/**
 * Sets a document to HELD, stores the hold reasons and stages one DocumentHeld event in one tenant transaction
 * (INV-REQ-479; the stored state is the single source of truth for "held"). A document in INGESTED, or already HELD,
 * may be held; any other state is refused and nothing is written (fail closed). A replay (HELD with equal reasons,
 * compared in step order) writes and stages nothing. No exception is caught: a failure rolls the transaction back.
 */
class HoldDocument(
    private val store: DocumentHoldStore,
    private val publisher: HeldEventPublisher,
    private val transaction: TransactionRunner,
) {
    /**
     * @throws IllegalArgumentException for a Proceed decision, before any port call.
     * @throws IllegalStateException for an unknown document or one in a state other than INGESTED or HELD.
     */
    fun hold(
        docUuid: UUID,
        decision: GateDecision,
    ) {
        val held = requireNotNull(decision as? GateDecision.Held) { "a Proceed decision does not hold a document" }
        val reasons = held.reasons
        require(reasons.isNotEmpty()) { "a hold needs at least one reason" }
        transaction.inTransaction {
            val doc = checkNotNull(store.lockForHold(docUuid)) { "document $docUuid not found" }
            check(doc.state == DocumentStates.INGESTED || doc.state == DocumentStates.HELD) {
                "document $docUuid in state ${doc.state} cannot be held"
            }
            if (doc.state == DocumentStates.HELD && doc.holdReasons == reasons) return@inTransaction
            store.markHeld(docUuid, reasons)
            publisher.stage(
                HeldEvent(
                    docUuid = doc.docUuid,
                    holdReasons = reasons,
                    entityRef = doc.entityRef,
                    direction = doc.direction,
                    documentDate = doc.documentDate,
                    prescribedPeriodRef = doc.prescribedPeriodRef,
                    postingTimestamp = doc.postingTimestamp,
                    invoiceIssuedOrReceived = doc.invoiceIssuedOrReceived,
                    partyRef = doc.partyRef,
                ),
            )
        }
    }
}
