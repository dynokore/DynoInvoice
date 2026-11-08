package com.dynokore.gst.access.application.grant

import com.dynokore.gst.access.domain.grant.Grant
import com.dynokore.gst.access.domain.grant.GrantStatus
import com.dynokore.gst.access.domain.grant.Scope

/**
 * Maps a grant to the one role-grant change it publishes (INV-Q-8972 A): the grant is carried once, with its role,
 * scope level, scope reference, validity and period span, and never expanded to entities or to actions. It reads
 * neither the entity directory nor the permission matrix: a replica resolves the covered entities from its structure
 * replica and the permitted actions from the matrix.
 */
class RoleGrantMapper {
    /** The one change for [changed] in its state after the change, at the version drawn once from [nextVersion]. */
    fun forChange(
        changed: Grant,
        nextVersion: () -> Long,
    ): List<RoleGrantChange> = listOf(change(changed, nextVersion()))

    /**
     * A reactivation: one ACTIVE change for each grant of [all] that is active, each at its own new version, so the
     * re-emitted records are higher than the revocation they follow and a replica holds them again.
     */
    fun forReactivation(
        all: List<Grant>,
        nextVersion: () -> Long,
    ): List<RoleGrantChange> =
        all
            .filter { it.status == GrantStatus.ACTIVE }
            .sortedBy { it.ref.value }
            .map { change(it, nextVersion()) }

    private fun change(
        grant: Grant,
        version: Long,
    ): RoleGrantChange {
        val period = grant.scope as? Scope.EntityPeriod
        return RoleGrantChange(
            tenant = grant.tenant,
            identity = grant.identity,
            grantRef = grant.ref,
            role = grant.role,
            state =
                when (grant.status) {
                    GrantStatus.ACTIVE -> RoleGrantState.ACTIVE
                    GrantStatus.REVOKED -> RoleGrantState.REMOVED
                    GrantStatus.PENDING -> error("a pending grant ${grant.ref.value} is not published")
                },
            scopeLevel = grant.scope.level,
            scopeRef = scopeRefOf(grant.scope),
            validFrom = grant.window.from,
            validUntil = grant.window.until,
            periodFrom = period?.periods?.from,
            periodTo = period?.periods?.to,
            authorityVersion = version,
        )
    }

    companion object {
        fun scopeRefOf(scope: Scope): String =
            when (scope) {
                is Scope.Tenant -> scope.tenant.value
                is Scope.Group -> scope.group.value
                is Scope.Entity -> scope.entity.value
                is Scope.EntityPeriod -> scope.entity.value
                is Scope.ConsentDerived -> scope.tenant.value
            }
    }
}
