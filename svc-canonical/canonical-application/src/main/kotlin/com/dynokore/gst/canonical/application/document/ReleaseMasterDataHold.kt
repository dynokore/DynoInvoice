package com.dynokore.gst.canonical.application.document

import com.dynokore.gst.canonical.domain.derivation.DerivationGate
import com.dynokore.gst.canonical.domain.derivation.DerivationStep
import com.dynokore.gst.canonical.domain.derivation.GateDecision
import com.dynokore.gst.canonical.domain.derivation.StepOutcome
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.util.UUID

/** The outcome of the release of one document. */
enum class ReleaseResult { RELEASED, NOT_FOUND, NOT_HELD, NOT_MASTER_DATA }

/**
 * Releases the named HELD documents whose stored reasons are all master-data codes back to INGESTED and stages one
 * DocumentReleased event each, in one tenant transaction (INV-REQ-479; MasterDataResolved names the documents, the
 * stored state is the source of truth, fail closed). A document that is unknown, not HELD, or held for anything other
 * than master-data codes (an empty list included) is skipped and touched in no way. No exception is caught: a failure
 * rolls the whole transaction back.
 */
class ReleaseMasterDataHold(
    private val store: DocumentReleaseStore,
    private val publisher: ReleasedEventPublisher,
    private val transaction: TransactionRunner,
) {
    /**
     * @return one entry per distinct uuid, in input order.
     * @throws IllegalStateException when the gate does not proceed although every step is complete.
     */
    fun release(docUuids: Collection<UUID>): Map<UUID, ReleaseResult> {
        if (docUuids.isEmpty()) return emptyMap()
        return transaction.inTransaction {
            val results = LinkedHashMap<UUID, ReleaseResult>()
            for (docUuid in docUuids.distinct()) {
                results[docUuid] = releaseOne(docUuid)
            }
            results
        }
    }

    private fun releaseOne(docUuid: UUID): ReleaseResult {
        val doc = store.lockForRelease(docUuid)
        val reasons = doc?.holdReasons.orEmpty()
        return when {
            doc == null -> {
                ReleaseResult.NOT_FOUND
            }

            doc.state != DocumentStates.HELD -> {
                ReleaseResult.NOT_HELD
            }

            reasons.isEmpty() || reasons.any { it !in MasterDataHoldCodes.CODES } -> {
                ReleaseResult.NOT_MASTER_DATA
            }

            else -> {
                val outcomes =
                    DerivationStep.entries.associateWith<DerivationStep, StepOutcome> {
                        StepOutcome.Complete
                    }
                check(DerivationGate.decide(outcomes) == GateDecision.Proceed) { "gate does not proceed for $docUuid" }
                store.markReleased(docUuid)
                publisher.stage(ReleasedEvent(docUuid, reasons, doc.entityRef))
                ReleaseResult.RELEASED
            }
        }
    }
}
