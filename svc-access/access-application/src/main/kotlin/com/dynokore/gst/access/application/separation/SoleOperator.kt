package com.dynokore.gst.access.application.separation

import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantAuditEntry
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.gst.access.domain.role.SeparationOfDuty
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

    /**
     * The active acknowledgement of the bound tenant, locked `FOR UPDATE` until the transaction ends, or null. Holders
     * are counted only after this lock is taken, so two requests cannot both pass the count (INV-AC-8288).
     */
    fun lockActive(): SoleOperatorAck?

    /**
     * The people who hold [role] in the bound tenant: a person holds a role when a grant of that role has a status
     * other than REVOKED, whatever its scope. A holder in another tenant never appears (INV-AC-8288).
     */
    fun holders(role: Role): Set<IdentityRef>

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

    /** Fewer than two different people hold `security.officer` or `tenant.admin` (INV-AC-8288 case C7). */
    data object SecondPersonRequired : WithdrawSoleOperatorOutcome

    /** A person still holds both roles (INV-AC-8288 case C6). */
    data object HolderRemains : WithdrawSoleOperatorOutcome
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
    private val scopePolicy: SoleOperatorScopePolicy,
) {
    fun handle(
        actor: String,
        ackRef: String,
    ): WithdrawSoleOperatorOutcome =
        tx.inTransaction {
            val ack = acks.find(ackRef) ?: return@inTransaction WithdrawSoleOperatorOutcome.NotFound
            if (!ack.active) return@inTransaction WithdrawSoleOperatorOutcome.AlreadyWithdrawn
            // the lock comes before the count; a request that waited for it sees the acknowledgement already withdrawn
            if (acks.lockActive()?.ref != ackRef) return@inTransaction WithdrawSoleOperatorOutcome.AlreadyWithdrawn
            // VP-81, read after the lock; a read that fails or names no known value fails the request
            val refusal = refusalFor(scopePolicy.current())
            if (refusal != null) return@inTransaction refusal
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

    /** The refusal the value of VP-81 gives while the holders are counted; null when the withdrawal may proceed. */
    private fun refusalFor(value: SoleOperatorScope): WithdrawSoleOperatorOutcome? {
        if (value == SoleOperatorScope.ANY_NUMBER) return null
        val officers = acks.holders(Role.SECURITY_OFFICER)
        val admins = acks.holders(Role.TENANT_ADMIN)
        // D3 before D2 (INV-AC-8288): two different people must hold one of the roles, then nobody may hold both
        return when {
            (officers + admins).size < 2 -> WithdrawSoleOperatorOutcome.SecondPersonRequired

            value == SoleOperatorScope.ONE_HOLDER_WITHDRAW_BLOCKED &&
                SeparationOfDuty.holdingBoth(officers, admins).isNotEmpty() -> WithdrawSoleOperatorOutcome.HolderRemains

            else -> null
        }
    }
}
