package com.dynokore.gst.tenancy.application.rendition

import com.dynokore.gst.tenancy.domain.AttestationState
import com.dynokore.gst.tenancy.domain.Renderer
import com.dynokore.gst.tenancy.domain.RenditionRules
import com.dynokore.platform.tenancykernel.TransactionRunner

/** Whether the entity is registered in the bound tenant (adapter: the entity store). */
fun interface RenditionEntities {
    fun exists(entityRef: String): Boolean
}

/** The attestation row of one entity and one document class (INV-REQ-1031). [attestedBy] is a user ID. */
data class RenditionAttestation(
    val entityRef: String,
    val documentClass: String,
    val renderer: Renderer,
    val attestedBy: String,
    val state: AttestationState,
)

/** The attestation rows and the last finding sequence per entity of the bound tenant (adapter: INV-T-16411). */
interface RenditionAttestationStore {
    /** Inserts or replaces the row of (entity, document class). */
    fun put(attestation: RenditionAttestation)

    fun find(
        entityRef: String,
        documentClass: String,
    ): RenditionAttestation?

    /** Sets [state] on every recorded row of the entity; returns the number of rows changed (0 for no rows). */
    fun setStateForEntity(
        entityRef: String,
        state: AttestationState,
    ): Int

    /** The last RenditionFindingChanged sequence applied for the entity, or null. */
    fun lastSequence(entityRef: String): Long?

    fun storeSequence(
        entityRef: String,
        sequence: Long,
    )
}

sealed interface RecordRenditionOutcome {
    data class Recorded(
        val attestation: RenditionAttestation,
    ) : RecordRenditionOutcome

    /** 403 SRT-004: `attestedBy` differs from the authenticated caller; nothing is stored (INV-Q-16397). */
    data object Forbidden : RecordRenditionOutcome

    /** 404 SRT-005: no such entity. */
    data object NotFound : RecordRenditionOutcome

    /** 400 SRT-001: unknown renderer or empty document class; nothing is stored. */
    data class Invalid(
        val reason: String,
    ) : RecordRenditionOutcome
}

/**
 * recordRenditionResponsibility (INV-REQ-1031, INV-REQ-1034): stores the attestation of one document class as CURRENT.
 * The stored attester is the caller's user ID. This service raises neither PAY-014 nor DEP-006 (INV-Q-16391).
 */
class RecordRenditionResponsibility(
    private val tx: TransactionRunner,
    private val entities: RenditionEntities,
    private val store: RenditionAttestationStore,
) {
    fun handle(
        caller: String,
        entityRef: String,
        documentClass: String,
        renderer: String?,
        attestedBy: String,
    ): RecordRenditionOutcome {
        if (!RenditionRules.attesterIsCaller(attestedBy, caller)) return RecordRenditionOutcome.Forbidden
        val parsed = Renderer.parse(renderer)
        return when {
            parsed == null -> {
                RecordRenditionOutcome.Invalid("renderer must be SELF, AGENT or AGGREGATOR")
            }

            documentClass.isBlank() -> {
                RecordRenditionOutcome.Invalid("documentClass is required")
            }

            else -> {
                tx.inTransaction {
                    if (!entities.exists(entityRef)) {
                        RecordRenditionOutcome.NotFound
                    } else {
                        val row =
                            RenditionAttestation(entityRef, documentClass, parsed, caller, AttestationState.CURRENT)
                        store.put(row)
                        RecordRenditionOutcome.Recorded(row)
                    }
                }
            }
        }
    }
}

/** One RenditionFindingChanged event (payload: entityRef, open, code, sequence, changedAt). */
data class RenditionFindingChanged(
    val entityRef: String,
    val open: Boolean,
    val sequence: Long,
)

enum class FindingResult {
    /** Newer sequence: stored; open rows were set REQUESTED_AGAIN when the finding is open. */
    APPLIED,

    /** Sequence not above the stored one: nothing changed. */
    IGNORED,
}

/**
 * Consumes RenditionFindingChanged (INV-Q-16391): `open` true sets REQUESTED_AGAIN on every recorded class of the
 * entity; `open` false changes no state; an event whose sequence is not above the stored one is ignored. Never
 * creates a row and raises no PAY-014 or DEP-006.
 */
class ApplyRenditionFinding(
    private val tx: TransactionRunner,
    private val store: RenditionAttestationStore,
) {
    fun handle(event: RenditionFindingChanged): FindingResult =
        tx.inTransaction {
            if (!RenditionRules.isNewer(event.sequence, store.lastSequence(event.entityRef))) {
                FindingResult.IGNORED
            } else {
                store.storeSequence(event.entityRef, event.sequence)
                RenditionRules.stateAfterFinding(event.open)?.let { store.setStateForEntity(event.entityRef, it) }
                FindingResult.APPLIED
            }
        }
}
