package com.dynokore.platform.serviceruntime.identity

import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.tenancykernel.TenantId

/**
 * The caller of an internal operation, from the propagated end-user token (INV-REQ-1202 b2 and b4, API-24,
 * CS-23; owner ruling INV-Q-3716 A): [actor] is the token's `sub` and [tenant] its `tenant_id`, never a request
 * field; [entities] are the principal's granted entities, read from the authority replica by the service's
 * resolver, not from the token. A token without a tenant (a platform operator) has none and no entities. Roles
 * are not here: they are re-checked in the owning service against the authority replica (CS-23).
 */
data class EndUserPrincipal(
    val actor: String,
    val tenant: TenantId?,
    val entities: Set<EntityRef>,
) {
    init {
        require(actor.isNotBlank()) { "actor must not be blank" }
        require(tenant != null || entities.isEmpty()) { "an entity set belongs to a tenant" }
        // INV-REQ-1226 b7: the reserved platform tenant is never bound from a token.
        require(tenant == null || !tenant.isPlatform) { "a token never carries the reserved platform tenant" }
    }

    /** API-24: an entityRef inside the principal's granted entities. */
    fun covers(entity: EntityRef): Boolean = entity in entities
}

/**
 * Verifies the propagated end-user token (the inbound `Authorization` header) and reads its principal. The
 * owning service supplies it with its resource-server configuration over the `IdentityProvider` binding
 * (STK-27). Layout per owner ruling INV-Q-3716 A (INV-REQ-1202 b4): the actor is the token's `sub`, the tenant
 * its `tenant_id`, and the entity set the principal's granted entities read from the authority replica; the
 * default resolver ships with INV-T-3335. Returns `null` when the token is missing, invalid or expired (401).
 */
fun interface EndUserPrincipalResolver {
    fun resolve(authorization: String?): EndUserPrincipal?
}
