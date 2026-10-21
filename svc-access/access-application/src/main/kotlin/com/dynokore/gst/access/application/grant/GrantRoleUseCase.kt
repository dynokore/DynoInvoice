package com.dynokore.gst.access.application.grant

import com.dynokore.gst.access.application.separation.SoleOperatorAcks
import com.dynokore.gst.access.domain.grant.Grant
import com.dynokore.gst.access.domain.grant.GrantHeader
import com.dynokore.gst.access.domain.grant.GrantStatus
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.gst.access.domain.role.RoleCatalogue
import com.dynokore.gst.access.domain.role.SeparationOfDuty
import com.dynokore.platform.tenancykernel.TransactionRunner

/**
 * `grantRole` (INV-REQ-864, INV-REQ-859): refuses a grant without a scope or with an unknown role; otherwise stores the
 * grant (a repeat of the same identity, role and scope returns the original), publishes AuthorityChanged for every
 * entity
 * the scope reaches in the same transaction, and writes the audit entry (INV-REQ-861). The identity row is locked
 * before the
 * authority versions are drawn, so versions per identity follow commit order (INV-API-3325).
 */
class GrantRoleUseCase(
    private val tx: TransactionRunner,
    private val grants: GrantRepository,
    directory: EntityDirectory,
    matrix: PermissionMatrix,
    private val publisher: AuthorityChangePublisher,
    private val audit: GrantAudit,
    private val catalogue: RoleCatalogue,
    private val soleOperators: SoleOperatorAcks,
) {
    private val records = AuthorityRecords(directory, matrix)

    fun handle(command: GrantRoleCommand): GrantRoleOutcome {
        val scope = command.scope
        val role = Role.ofWire(command.roleWire)
        return when {
            scope == null -> GrantRoleOutcome.ScopeMissing
            role == null || !catalogue.contains(role) -> GrantRoleOutcome.UnknownRole(command.roleWire)
            else -> tx.inTransaction { issue(command, role, scope) }
        }
    }

    private fun issue(
        command: GrantRoleCommand,
        role: Role,
        scope: Scope,
    ): GrantRoleOutcome {
        grants.lockIdentity(command.identity, command.granteeKind)
        val existing = grants.findByRepeatKey(command.identity, role.wire, scope)
        if (existing != null && existing.status != GrantStatus.REVOKED) {
            return GrantRoleOutcome.Granted(existing, repeated = true)
        }
        val held = grants.findByIdentity(command.identity).filter { it.status != GrantStatus.REVOKED }.map { it.role }
        if (SeparationOfDuty.completesConflict(held.toSet(), role) && soleOperators.findActive() == null) {
            return GrantRoleOutcome.SoleOperatorAckRequired
        }
        val grant =
            Grant.issue(
                GrantHeader(grants.nextGrantRef(), command.tenant, command.identity),
                role,
                scope,
                command.window,
            )
        grants.insert(grant)
        publisher.publish(
            records.forChange(
                command.tenant,
                command.identity,
                grant,
                grants.findByIdentity(command.identity),
                grants::nextAuthorityVersion,
            ),
        )
        audit.record(
            GrantAuditEntry(
                actor = command.actor,
                action = "grantRole",
                scope = "${scope.level}:${AuthorityRecords.scopeRefOf(scope)}",
                reason = null,
                priorStateJson = null,
            ),
        )
        return GrantRoleOutcome.Granted(grant, repeated = false)
    }
}

/**
 * `revokeGrant`: revokes a grant, publishes the removal for every entity it reached and audits it with the prior state.
 */
class RevokeGrantUseCase(
    private val tx: TransactionRunner,
    private val grants: GrantRepository,
    directory: EntityDirectory,
    matrix: PermissionMatrix,
    private val publisher: AuthorityChangePublisher,
    private val audit: GrantAudit,
) {
    private val records = AuthorityRecords(directory, matrix)

    fun handle(
        actor: String,
        ref: com.dynokore.gst.access.domain.grant.GrantRef,
    ): RevokeGrantOutcome =
        tx.inTransaction {
            val before = grants.find(ref) ?: return@inTransaction RevokeGrantOutcome.NotFound
            grants.lockIdentity(before.identity, com.dynokore.gst.access.domain.grant.GranteeKind.STAFF)
            val current = grants.find(ref) ?: return@inTransaction RevokeGrantOutcome.NotFound
            val after = current.revoked()
            if (after === current) return@inTransaction RevokeGrantOutcome.AlreadyRevoked(current)
            grants.update(current, after)
            publisher.publish(
                records.forChange(
                    after.tenant,
                    after.identity,
                    after,
                    grants.findByIdentity(after.identity),
                    grants::nextAuthorityVersion,
                ),
            )
            audit.record(
                GrantAuditEntry(
                    actor = actor,
                    action = "revokeGrant",
                    scope = "${current.scope.level}:${AuthorityRecords.scopeRefOf(current.scope)}",
                    reason = null,
                    priorStateJson = """{"status":"${current.status}","role":"${current.role.wire}"}""",
                ),
            )
            RevokeGrantOutcome.Revoked(after)
        }
}
