package com.dynokore.platform.serviceruntime.policy

import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.tenancykernel.TenantId

/**
 * The roles an actor holds at the scope of a `setPolicy` or `acknowledgeControlRelaxation` request, answered by the
 * owning service (INV-T-5180, coordinator ruling INV-Q-5172). The role vocabulary is product vocabulary, so it
 * stays out of this L1 library (ARC-16): the service binds this port against its own authority replica, as it binds
 * `JobCommandAuthorizer` for jobs. config-resolver's `PolicyWriter` then checks the roles against the setting role
 * of the variation point (INV-REQ-1239 b3) and, for an acknowledgement, against `asRole`.
 *
 * The contract of an implementation (coordinator ruling INV-Q-5235):
 * - The roles come only from grants on the exact [entityRef] for an entity-scoped level (ENTITY, FEED,
 *   AP_CONNECTION, CLIENT_ENTITY), never from a grant on another entity.
 * - They are answered from the authority replica while its lag is within the bound, and from the access-svc
 *   fallback (the `AuthorityReplica.decide` path) beyond it, never from the stale replica.
 * - An actor with no role at the scope gets the empty set. The caller treats an empty set, an exception and a
 *   missing answer alike as 403 SRT-004 with nothing stored (fail closed, CS-23, API-24).
 */
fun interface PolicyRoleResolver {
    /**
     * @param actor the token's `sub`.
     * @param tenant the token's tenant; the reserved platform tenant for [ScopeLevel.INSTALLATION] with a
     *   tenantless token (INV-Q-2927 option D, INV-Q-4115).
     * @param scopeRef the request's `scopeRef`; `null` exactly for [ScopeLevel.INSTALLATION].
     * @param entityRef the request's `entityRef`; present exactly for an entity-scoped level.
     */
    fun rolesOf(
        actor: String,
        tenant: TenantId,
        level: ScopeLevel,
        scopeRef: String?,
        entityRef: String?,
    ): Set<String>
}
