package com.dynokore.gst.access.adapters.http

import com.dynokore.gst.access.application.grant.EntityDirectory
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.GrantStatus
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.grant.Target
import com.dynokore.gst.access.domain.grant.TenantAuthority
import com.dynokore.gst.access.domain.role.Allowance
import com.dynokore.gst.access.domain.role.Permission
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.configresolver.Actor
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.configresolver.SetterPermission
import com.dynokore.platform.tenancykernel.TenantId

/**
 * The [SetterPermission] of access-svc (INV-Q-10027, INV-NOTE-10639): this service is the authority and holds no
 * replica, so it decides the permission key of a variation point from the actor's own live grants (or the configured
 * installation roles) and its own [PermissionMatrix]. A role qualifies when the matrix gives it the key as PERMITTED or
 * WITHIN_SCOPE; no role is named here (OWASP ASVS V4.1, NIST SP 800-53 AC-6). TENANT, INSTALLATION, ENTITY and
 * CLIENT_ENTITY are decided; every other level, the platform tenant at TENANT, a blank key, an unknown entity, a failed
 * read and an exception are false (fail closed).
 */
class LiveSetterPermission(
    private val live: LiveTenantGrants,
    private val matrix: PermissionMatrix,
    private val installation: InstallationRoleDirectory,
    private val directory: EntityDirectory? = null,
) : SetterPermission {
    override fun holds(
        actor: Actor,
        tenant: TenantId,
        level: ScopeLevel,
        scopeRef: String?,
        entityRef: String?,
        permissionKey: String,
    ): Boolean {
        if (permissionKey.isBlank() || permissionKey.length > MAX_KEY) return false
        val permission = Permission(permissionKey)
        val subject = actor.principal
        return runCatching {
            when {
                level == ScopeLevel.INSTALLATION && scopeRef == null -> {
                    installation.rolesOf(subject).any { allows(it, permission) }
                }

                level == ScopeLevel.TENANT && scopeRef == tenant.toString() -> {
                    live.read(subject, tenant) { grants, ref, at ->
                        TenantAuthority.tenantRoles(grants, ref, at).any { allows(it, permission) }
                    } == true
                }

                entityRef != null && level in ENTITY_LEVELS && (level == ScopeLevel.ENTITY || scopeRef == entityRef) -> {
                    entityHolds(subject, tenant, entityRef, permission)
                }

                else -> {
                    false
                }
            }
        }.getOrDefault(false)
    }

    /**
     * True when one live grant of [subject] covers exactly [entityRef] (INV-Q-5235): status ACTIVE, window valid at the
     * read time, scope not consent-derived (an agent does not set the values of a client), and its role holds the key.
     * An entity the structure does not know gives false.
     */
    private fun entityHolds(
        subject: String,
        tenant: TenantId,
        entityRef: String,
        permission: Permission,
    ): Boolean {
        val entity = EntityRef(entityRef)
        return live.read(subject, tenant) { grants, _, at ->
            // the structure answers for the bound tenant, so the membership is asked inside the binding
            val membership = directory?.membership(entity)
            membership != null &&
                grants.any {
                    it.status == GrantStatus.ACTIVE &&
                        it.window.isValidAt(at) &&
                        it.scope !is Scope.ConsentDerived &&
                        it.scope.covers(Target(entity), membership) &&
                        allows(it.role, permission)
                }
        } == true
    }

    private fun allows(
        role: Role,
        permission: Permission,
    ): Boolean = matrix.allowance(role, permission).let { it == Allowance.PERMITTED || it == Allowance.WITHIN_SCOPE }

    private companion object {
        const val MAX_KEY = 64
        val ENTITY_LEVELS = setOf(ScopeLevel.ENTITY, ScopeLevel.CLIENT_ENTITY)
    }
}
