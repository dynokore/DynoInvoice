package com.dynokore.gst.access.application.grant

import com.dynokore.gst.access.application.separation.SoleOperatorAcks
import com.dynokore.gst.access.application.separation.SoleOperatorScope
import com.dynokore.gst.access.application.separation.SoleOperatorScopePolicy
import com.dynokore.gst.access.domain.grant.Grant
import com.dynokore.gst.access.domain.grant.GrantHeader
import com.dynokore.gst.access.domain.grant.GrantStatus
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.gst.access.domain.role.RoleCatalogue
import com.dynokore.gst.access.domain.role.SeparationOfDuty
import com.dynokore.gst.access.domain.role.TenantGrantableRoles
import com.dynokore.platform.tenancykernel.TransactionRunner

/** What `grantRole` checks beyond the grant itself: the roles of the installation's form and the separation of duty. */
class GrantRoleRules(
    val catalogue: RoleCatalogue,
    val soleOperators: SoleOperatorAcks,
    val soleOperatorScope: SoleOperatorScopePolicy,
)

/** What a granted role writes besides the grant: the AuthorityChanged events and the audit entry. */
class GrantRoleEffects(
    val publisher: AuthorityChangePublisher,
    val audit: GrantAudit,
)

/**
 * `grantRole` (INV-REQ-864, INV-REQ-859): refuses a grant without a scope, with an unknown role or with an Operator
 * role (INV-Q-9069 A, before any lock or write); otherwise stores the
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
    private val effects: GrantRoleEffects,
    private val rules: GrantRoleRules,
) {
    private val records = AuthorityRecords(directory, matrix)

    fun handle(command: GrantRoleCommand): GrantRoleOutcome {
        val scope = command.scope
        val role = Role.ofWire(command.roleWire)
        return when {
            scope == null -> GrantRoleOutcome.ScopeMissing
            role == null || !rules.catalogue.contains(role) -> GrantRoleOutcome.UnknownRole(command.roleWire)
            TenantGrantableRoles.refuses(role) -> GrantRoleOutcome.OperatorRoleRefused
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
        return separated(command, role, scope)
    }

    /** The separation of duty: the lock on the acknowledgement comes before the count of the people holding both. */
    private fun separated(
        command: GrantRoleCommand,
        role: Role,
        scope: Scope,
    ): GrantRoleOutcome {
        val held = grants.findByIdentity(command.identity).filter { it.status != GrantStatus.REVOKED }.map { it.role }
        return when {
            !SeparationOfDuty.completesConflict(held.toSet(), role) -> insert(command, role, scope)

            rules.soleOperators.lockActive() == null -> GrantRoleOutcome.SoleOperatorAckRequired

            // VP-81, read after the lock; a failed read or an unknown value fails the request (nothing is written)
            rules.soleOperatorScope.current() == SoleOperatorScope.ANY_NUMBER -> insert(command, role, scope)

            otherPeopleHoldingBoth(command.identity).isNotEmpty() -> GrantRoleOutcome.SecondHolderRefused

            else -> insert(command, role, scope)
        }
    }

    /** The people other than [identity] who hold both roles in the bound tenant (VP-81 one-holder values). */
    private fun otherPeopleHoldingBoth(identity: IdentityRef): Set<IdentityRef> =
        SeparationOfDuty.holdingBoth(
            rules.soleOperators.holders(Role.SECURITY_OFFICER),
            rules.soleOperators.holders(Role.TENANT_ADMIN),
        ) - identity

    private fun insert(
        command: GrantRoleCommand,
        role: Role,
        scope: Scope,
    ): GrantRoleOutcome {
        val grant =
            Grant.issue(
                GrantHeader(grants.nextGrantRef(), command.tenant, command.identity),
                role,
                scope,
                command.window,
            )
        grants.insert(grant)
        effects.publisher.publish(
            records.forChange(
                command.tenant,
                command.identity,
                grant,
                grants.findByIdentity(command.identity),
                grants::nextAuthorityVersion,
            ),
        )
        effects.audit.record(
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
