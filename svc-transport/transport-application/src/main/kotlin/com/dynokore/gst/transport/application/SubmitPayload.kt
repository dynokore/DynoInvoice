package com.dynokore.gst.transport.application

import com.dynokore.gst.transport.domain.DocRef
import com.dynokore.gst.transport.domain.EntityRef
import com.dynokore.gst.transport.domain.TransportOutcome
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** The entity has no adapter assigned: transport never picks one on its own. */
class NoAdapterConfigured(
    entity: EntityRef,
) : IllegalStateException("entity $entity has no Access Point adapter assigned")

/** The adapter an entity or an attempt names is not registered in this installation. */
class AdapterNotRegistered(
    adapter: String,
) : IllegalStateException("Access Point adapter $adapter is not registered")

/** The Doc UUID is already recorded under another entity: to this caller it does not exist (INV-Q-8172, SRT-005). */
class DocUuidRecordedUnderOtherEntity(
    docUuid: UUID,
) : IllegalStateException("Doc UUID $docUuid is recorded under another entity")

/**
 * The submit operation of the internal transport interface (INV-REQ-795): a payload for a Doc UUID and a version in,
 * accepted with acknowledgement, rejected with reasons or transient failure out. The entity only selects the adapter.
 *
 * Routing: a version already in flight stays on the adapter of its first attempt, otherwise the entity's current
 * adapter is used, so a cut-over mid-period moves new despatches only (INV-AC-846). The first attempt of a Doc UUID
 * records its entity; a request for the Doc UUID under another entity is refused (INV-Q-8172). A repeat of a settled
 * Doc UUID and version returns the original outcome and makes no second egress.
 */
class SubmitPayload(
    private val adapters: AccessPointAdapters,
    private val attempts: AttemptLog,
    private val assignments: EntityAdapterAssignments,
    private val clock: Clock,
) {
    /** When the receipt of the accepted [doc] was recorded, for the acknowledgement of the response. */
    fun receivedAt(doc: DocRef): Instant? = attempts.acceptedAt(doc)

    private fun requireRecordedEntity(
        entity: EntityRef,
        doc: DocRef,
    ) {
        val recorded = attempts.entityOf(doc.docUuid)
        if (recorded != null && recorded != entity) throw DocUuidRecordedUnderOtherEntity(doc.docUuid)
    }

    fun submit(
        entity: EntityRef,
        doc: DocRef,
        payload: String,
    ): TransportOutcome {
        requireRecordedEntity(entity, doc)
        attempts.settledOutcome(doc)?.let { return it }
        val adapterId =
            attempts.adapterInFlight(doc) ?: assignments.adapterFor(entity) ?: throw NoAdapterConfigured(entity)
        val port = adapters.find(adapterId) ?: throw AdapterNotRegistered(adapterId.value)
        val attempt = attempts.begin(entity, doc, adapterId, clock.instant())
        // an adapter that fails outside its own classification is still only a transient failure upstream
        val outcome = runCatching { port.submit(doc, payload) }.getOrElse { TransportOutcome.TransientFailure }
        attempts.complete(attempt, outcome, clock.instant())
        return outcome
    }
}
