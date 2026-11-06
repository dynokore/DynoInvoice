package com.dynokore.gst.access.application.approval

import com.dynokore.gst.access.application.authorise.Authorise
import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantAuditEntry
import com.dynokore.gst.access.domain.grant.Decision
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.Target
import com.dynokore.gst.access.domain.role.Permission
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Clock
import java.time.Instant

/** The store that also writes (kept apart from the read port, which other adapters already implement). */
interface RecordsMappingAcknowledgements : MappingAcknowledgements {
    /**
     * Stores the acknowledgement of exactly this pair for [by] and returns the stored row. A pair already stored is
     * not overwritten: the first acknowledgement stays the evidence (repeatKey of the contract).
     */
    fun record(
        packVersion: String,
        clientRef: String,
        by: String,
        at: Instant,
    ): MappingAcknowledgement
}

sealed interface AcknowledgeMappingOutcome {
    data class Recorded(
        val acknowledgement: MappingAcknowledgement,
        val repeated: Boolean,
    ) : AcknowledgeMappingOutcome

    /** The caller does not hold `mapping-effect.acknowledge` on the client entity. */
    data object NotSignatory : AcknowledgeMappingOutcome
}

/**
 * `acknowledgeMapping` (INV-REQ-900, INV-REQ-996): the client signatory records the acknowledgement of an approved
 * pack version for one client entity, as evidence. The scoped key `mapping-effect.acknowledge` is decided per request
 * on the client entity (INV-Q-10027); the audit entry names the acting user ID, never a role (INV-Q-10647). A repeat
 * of the same pack version and client entity returns the first acknowledgement and writes no second audit entry.
 */
class AcknowledgeMappingUseCase(
    private val tx: TransactionRunner,
    private val store: RecordsMappingAcknowledgements,
    private val signatory: Authorise,
    private val audit: GrantAudit,
    private val clock: Clock,
) {
    fun handle(
        actor: IdentityRef,
        packVersion: String,
        client: EntityRef,
    ): AcknowledgeMappingOutcome =
        tx.inTransaction {
            if (signatory.handle(actor, MAPPING_EFFECT_ACKNOWLEDGE, Target(client)) !is Decision.Allow) {
                return@inTransaction AcknowledgeMappingOutcome.NotSignatory
            }
            val earlier = store.find(packVersion, client.value)
            if (earlier != null) return@inTransaction AcknowledgeMappingOutcome.Recorded(earlier, repeated = true)
            val stored = store.record(packVersion, client.value, actor.value, clock.instant())
            audit.record(
                GrantAuditEntry(
                    actor = actor.value,
                    action = "acknowledgeMapping",
                    scope = "entity:${client.value}",
                    reason = null,
                    priorStateJson = null,
                ),
            )
            AcknowledgeMappingOutcome.Recorded(stored, repeated = false)
        }

    private companion object {
        val MAPPING_EFFECT_ACKNOWLEDGE = Permission("mapping-effect.acknowledge")
    }
}
