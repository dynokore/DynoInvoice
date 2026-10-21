package com.dynokore.gst.access.application.separation

import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantAuditEntry
import com.dynokore.platform.tenancykernel.TransactionRunner

/** A tenant's recorded sole-operator acknowledgement (INV-REQ-994): one person may hold the conflicting roles. */
data class SoleOperatorAck(
    val ref: String,
    val tenantRef: String,
    val compensatingControls: String,
    val active: Boolean,
)

/** Persistence of acknowledgements under the bound tenant. At most one is active per tenant (repeatKey tenantRef). */
interface SoleOperatorAcks {
    /** The active acknowledgement of the bound tenant, or null. */
    fun findActive(): SoleOperatorAck?

    fun find(ref: String): SoleOperatorAck?

    /** Stores a new active acknowledgement for the bound tenant. */
    fun insert(compensatingControls: String): SoleOperatorAck

    /** Marks [ref] withdrawn. */
    fun withdraw(ref: String)
}

sealed interface RecordSoleOperatorOutcome {
    data class Recorded(
        val ack: SoleOperatorAck,
        val repeated: Boolean,
    ) : RecordSoleOperatorOutcome

    /** The compensating controls are blank: nothing is acknowledged. */
    data object ControlsMissing : RecordSoleOperatorOutcome
}

sealed interface WithdrawSoleOperatorOutcome {
    data object Withdrawn : WithdrawSoleOperatorOutcome

    /** Already withdrawn: a no-op (API-15). */
    data object AlreadyWithdrawn : WithdrawSoleOperatorOutcome

    data object NotFound : WithdrawSoleOperatorOutcome
}

/** `recordSoleOperator`: a repeat for the same tenant returns the active acknowledgement; the change is audited. */
class RecordSoleOperatorUseCase(
    private val tx: TransactionRunner,
    private val acks: SoleOperatorAcks,
    private val audit: GrantAudit,
) {
    fun handle(
        actor: String,
        tenantRef: String,
        compensatingControls: String,
    ): RecordSoleOperatorOutcome {
        if (compensatingControls.isBlank()) return RecordSoleOperatorOutcome.ControlsMissing
        return tx.inTransaction {
            val existing = acks.findActive()
            if (existing != null) return@inTransaction RecordSoleOperatorOutcome.Recorded(existing, repeated = true)
            val ack = acks.insert(compensatingControls)
            audit.record(
                GrantAuditEntry(
                    actor = actor,
                    action = "recordSoleOperator",
                    scope = "tenant:$tenantRef",
                    reason = null,
                    priorStateJson = null,
                ),
            )
            RecordSoleOperatorOutcome.Recorded(ack, repeated = false)
        }
    }
}

/** `withdrawSoleOperatorAcknowledgement`: marks the acknowledgement withdrawn and audits it with the prior state. */
class WithdrawSoleOperatorUseCase(
    private val tx: TransactionRunner,
    private val acks: SoleOperatorAcks,
    private val audit: GrantAudit,
) {
    fun handle(
        actor: String,
        ackRef: String,
    ): WithdrawSoleOperatorOutcome =
        tx.inTransaction {
            val ack = acks.find(ackRef) ?: return@inTransaction WithdrawSoleOperatorOutcome.NotFound
            if (!ack.active) return@inTransaction WithdrawSoleOperatorOutcome.AlreadyWithdrawn
            acks.withdraw(ackRef)
            audit.record(
                GrantAuditEntry(
                    actor = actor,
                    action = "withdrawSoleOperatorAcknowledgement",
                    scope = "tenant:${ack.tenantRef}",
                    reason = null,
                    priorStateJson = """{"status":"ACTIVE","ackRef":"${ack.ref}"}""",
                ),
            )
            WithdrawSoleOperatorOutcome.Withdrawn
        }
}
