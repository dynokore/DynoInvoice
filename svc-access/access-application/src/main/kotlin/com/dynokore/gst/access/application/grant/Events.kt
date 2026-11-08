package com.dynokore.gst.access.application.grant

import com.dynokore.gst.access.domain.grant.GrantRef
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.ScopeLevel
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.role.Role
import java.time.Instant
import java.time.LocalDate

/** Whether a role grant is held or removed (the wire `state` of `roleGrantRecord`). */
enum class RoleGrantState { ACTIVE, REMOVED }

/**
 * One role grant of one identity after a change (INV-Q-8972 A): the role, the scope level and reference, the validity
 * and, for an entity-period scope only, the period span. [authorityVersion] comes from access-svc's one sequence
 * (INV-REQ-1201 behaviour 1); a revoke of the same grant has the same [grantRef] and a higher version.
 */
data class RoleGrantChange(
    val tenant: TenantRef,
    val identity: IdentityRef,
    val grantRef: GrantRef,
    val role: Role,
    val state: RoleGrantState,
    val scopeLevel: ScopeLevel,
    val scopeRef: String,
    val validFrom: Instant,
    val validUntil: Instant?,
    val periodFrom: LocalDate?,
    val periodTo: LocalDate?,
    val authorityVersion: Long,
)

/**
 * Publishes AuthorityChanged (INV-API-3325) through outbox-and-queue in the command transaction: one event per change,
 * all appended before the transaction commits. The adapter owns the mapping to the contract event (TPL-05).
 */
interface AuthorityChangePublisher {
    fun publish(changes: List<RoleGrantChange>)
}

/** What a grant change records in the audit log: actor, action, scope, reason and prior state (INV-REQ-861). */
data class GrantAuditEntry(
    val actor: String,
    val action: String,
    val scope: String,
    val reason: String?,
    val priorStateJson: String?,
)

/** Writes the audit entry through audit-log, in the command transaction; the entry cannot be edited afterwards. */
interface GrantAudit {
    fun record(entry: GrantAuditEntry)
}
