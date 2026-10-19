package com.dynokore.gst.access.application.grant

import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.Grant
import com.dynokore.gst.access.domain.grant.GrantStatus
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.role.PermissionMatrix

/**
 * Builds the per-entity grant records of one grant change (INV-API-3325): a tenant or group grant is expanded by access-svc
 * into one record per entity under the scope, so no replica resolves a scope through its structure replica.
 */
internal class AuthorityRecords(
    private val directory: EntityDirectory,
    private val matrix: PermissionMatrix,
) {
    /**
     * One record per entity [changed] reaches, each carrying the identity's whole state on that entity from [all], its
     * grants after the change; [nextVersion] draws the authority sequence value of each record.
     */
    fun forChange(
        tenant: TenantRef,
        identity: IdentityRef,
        changed: Grant,
        all: List<Grant>,
        nextVersion: () -> Long,
    ): List<AuthorityGrantRecord> {
        val entities = directory.entitiesCoveredBy(changed.scope).sortedBy { it.value }
        return entities.map { entity ->
            AuthorityGrantRecord(
                tenant = tenant,
                identity = identity,
                entity = entity,
                authorityVersion = nextVersion(),
                actions = actionsOn(entity, all),
                windowFrom = changed.window.from,
                windowTo = changed.window.until,
            )
        }
    }

    private fun actionsOn(
        entity: EntityRef,
        all: List<Grant>,
    ): List<GrantedAction> {
        val membership = directory.membership(entity) ?: return emptyList()
        return all
            .filter { it.status == GrantStatus.ACTIVE && it.scope.reaches(entity, membership) }
            .flatMap { grant ->
                matrix.permissionsOf(grant.role).sortedBy { it.value }.map { permission ->
                    GrantedAction(
                        action = permission.value,
                        scopeLevel = grant.scope.level,
                        scopeRef = scopeRefOf(grant.scope),
                        validFrom = grant.window.from,
                        validUntil = grant.window.until,
                        periodFrom = (grant.scope as? Scope.EntityPeriod)?.periods?.from,
                        periodTo = (grant.scope as? Scope.EntityPeriod)?.periods?.to,
                    )
                }
            }
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
