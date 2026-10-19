package com.dynokore.gst.access.application.grant

import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.ScopeLevel
import com.dynokore.gst.access.domain.grant.TenantRef
import java.time.Instant
import java.time.LocalDate

/**
 * One action an identity holds on an entity, with the scope it comes from and its validity (INV-API-3325 grant record).
 */
data class GrantedAction(
    val action: String,
    val scopeLevel: ScopeLevel,
    val scopeRef: String,
    val validFrom: Instant,
    val validUntil: Instant?,
    val periodFrom: LocalDate?,
    val periodTo: LocalDate?,
)

/**
 * The full grant state, after a change, of one (tenant, identity, entity); an empty [actions] is an explicit removal
 * that
 * a replica keeps as a tombstone. [authorityVersion] comes from access-svc's one sequence (INV-REQ-1201 behaviour 1).
 */
data class AuthorityGrantRecord(
    val tenant: TenantRef,
    val identity: IdentityRef,
    val entity: EntityRef,
    val authorityVersion: Long,
    val actions: List<GrantedAction>,
    val windowFrom: Instant,
    val windowTo: Instant?,
)

/**
 * Publishes AuthorityChanged (INV-API-3325) through outbox-and-queue in the command transaction: one event per record,
 * all
 * appended before the transaction commits. The adapter owns the mapping to the contract event (TPL-05).
 */
interface AuthorityChangePublisher {
    fun publish(records: List<AuthorityGrantRecord>)
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
