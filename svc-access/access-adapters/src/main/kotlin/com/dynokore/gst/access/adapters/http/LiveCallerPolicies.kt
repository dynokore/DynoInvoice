package com.dynokore.gst.access.adapters.http

import com.dynokore.gst.access.application.grant.EntityDirectory
import com.dynokore.gst.access.application.grant.GrantRepository
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.Grant
import com.dynokore.gst.access.domain.grant.GrantStatus
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.grant.Target
import com.dynokore.gst.access.domain.grant.TenantAuthority
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.gst.access.domain.role.Role
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
 * One configured installation-scope role (INV-Q-8789 A): [subject] holds [role] at the INSTALLATION level. The role is
 * `platform.engineer` or `deployment.operator` by equality and nothing else; the subject is not blank. Anything else
 * is refused when the entry is built, so the service does not start.
 */
class InstallationRole(
    val subject: String,
    val role: Role,
) {
    companion object {
        private val ALLOWED = setOf(Role.PLATFORM_ENGINEER, Role.DEPLOYMENT_OPERATOR)

        /** The entry for [subject] and the wire name [role]; throws [IllegalArgumentException] naming the property. */
        fun of(
            subject: String?,
            role: String?,
        ): InstallationRole {
            require(!subject.isNullOrBlank()) { "access.installation-roles: the subject of an entry must not be blank" }
            val parsed = role?.let { Role.ofWire(it) }
            require(parsed != null && parsed in ALLOWED) {
                "access.installation-roles: the role of an entry must be platform.engineer or deployment.operator"
            }
            return InstallationRole(subject, parsed)
        }
    }
}

/**
 * The roles `setPolicy` checks (INV-REQ-2756 item 1). At the TENANT level with the actor's own tenant as scope
 * reference: those of the actor's live Tenant-scope grants. At the INSTALLATION level (scope reference null): the roles
 * of the configured [installationRoles] whose subject equals the actor exactly (INV-Q-8789 A); no grant counts there.
 * Any other case, a failed read included, is the empty set, which the caller treats as 403.
 */
class TenantPolicyRoleResolver(
    private val live: LiveTenantGrants,
    installationRoles: List<InstallationRole> = emptyList(),
    private val directory: EntityDirectory? = null,
) : PolicyRoleResolver {
    private val installation: Map<String, Set<String>> =
        installationRoles.groupBy { it.subject }.mapValues { (_, entries) -> entries.map { it.role.wire }.toSet() }

    override fun rolesOf(
        actor: String,
        tenant: TenantId,
        level: ScopeLevel,
        scopeRef: String?,
        entityRef: String?,
    ): Set<String> =
        when {
            level == ScopeLevel.INSTALLATION && scopeRef == null -> {
                installation[actor].orEmpty()
            }

            level == ScopeLevel.TENANT && scopeRef == tenant.toString() -> {
                runCatching {
                    live.read(actor, tenant) { grants, ref, at ->
                        TenantAuthority.tenantRoles(grants, ref, at).map { it.wire }.toSet()
                    }
                }.getOrNull().orEmpty()
            }

            entityRef != null && level in ENTITY_LEVELS && (level == ScopeLevel.ENTITY || scopeRef == entityRef) -> {
                entityRoles(actor, tenant, entityRef)
            }

            else -> {
                emptySet()
            }
        }

    /**
     * The roles of the live grants of [actor] that cover exactly [entityRef] (INV-Q-5235): a grant of the whole tenant
     * or of a group that contains the entity, or of the entity itself. A consent-derived grant never counts: an agent
     * does not set the values of a client. An entity the structure does not know, and a failed read, give no role.
     */
    private fun entityRoles(
        actor: String,
        tenant: TenantId,
        entityRef: String,
    ): Set<String> =
        runCatching {
            val entity = EntityRef(entityRef)
            live
                .read(actor, tenant) { grants, _, at ->
                    // the structure answers for the bound tenant, so the membership is asked inside the binding
                    val membership = directory?.membership(entity)
                    if (membership == null) {
                        emptySet()
                    } else {
                        grants
                            .filter {
                                it.status == GrantStatus.ACTIVE &&
                                    it.window.isValidAt(at) &&
                                    it.scope !is Scope.ConsentDerived &&
                                    it.scope.covers(Target(entity), membership)
                            }.map { it.role.wire }
                            .toSet()
                    }
                }.orEmpty()
        }.getOrNull().orEmpty()

    private companion object {
        val ENTITY_LEVELS = setOf(ScopeLevel.ENTITY, ScopeLevel.CLIENT_ENTITY)
    }
}
