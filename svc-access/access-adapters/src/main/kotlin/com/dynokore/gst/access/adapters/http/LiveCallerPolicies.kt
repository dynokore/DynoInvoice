package com.dynokore.gst.access.adapters.http

import com.dynokore.gst.access.application.grant.GrantRepository
import com.dynokore.gst.access.domain.grant.Grant
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.TenantAuthority
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.serviceruntime.policy.PolicyRoleResolver
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Clock
import java.time.Instant

/**
 * The live read both caller policies share: the actor's own grants in this service's table, read in one transaction
 * under [tenant], nothing cached (INV-REQ-1214). This service is the authority, so no replica is read.
 */
class LiveTenantGrants(
    private val tenants: TenantContext,
    private val tx: TransactionRunner,
    private val grants: GrantRepository,
    private val clock: Clock,
) {
    /** The result of [read] on the grants of [actor] in [tenant]; null when the tenant is the platform tenant. */
    fun <T> read(
        actor: String,
        tenant: TenantId,
        read: (grants: List<com.dynokore.gst.access.domain.grant.Grant>, tenant: TenantRef, at: Instant) -> T,
    ): T? {
        if (tenant.isPlatform) return null
        val identity = IdentityRef(actor)
        val ref = TenantRef(tenant.toString())
        return tenants.bind(tenant, actor) {
            tx.inTransaction { read(grants.findByIdentity(identity), ref, clock.instant()) }
        }
    }
}

/**
 * Allows when the caller holds one live Tenant-scope grant of the bound tenant whose role has `user.manage` PERMITTED
 * in the matrix. Anything else, a failed read included, refuses; an exception is never turned into true.
 */
class LiveGrantManagerCheck(
    private val tenants: TenantContext,
    private val live: LiveTenantGrants,
    private val matrix: PermissionMatrix,
) : GrantManagerCheck {
    override fun mayManageGrants(): Boolean =
        runCatching {
            val actor = tenants.currentActor() ?: return false
            live.read(actor, tenants.current()) { grants, tenant, at ->
                TenantAuthority.managesGrants(grants, tenant, matrix, at)
            } == true
        }.getOrDefault(false)
}

/**
 * The roles `setPolicy` checks (INV-REQ-2756 item 1): those of the actor's live Tenant-scope grants, only for the
 * TENANT level and the actor's own tenant as scope reference; any other case, a failed read included, is the empty
 * set, which the caller treats as 403.
 */
class TenantPolicyRoleResolver(
    private val live: LiveTenantGrants,
) : PolicyRoleResolver {
    override fun rolesOf(
        actor: String,
        tenant: TenantId,
        level: ScopeLevel,
        scopeRef: String?,
        entityRef: String?,
    ): Set<String> =
        if (level != ScopeLevel.TENANT || scopeRef != tenant.toString()) {
            emptySet()
        } else {
            runCatching {
                live.read(actor, tenant) { grants, ref, at ->
                    TenantAuthority.tenantRoles(grants, ref, at).map { it.wire }.toSet()
                }
            }.getOrNull().orEmpty()
        }
}
