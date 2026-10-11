package com.dynokore.platform.serviceruntime.identity

import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.tenancykernel.TenantId

/**
 * The caller of an internal operation, from the propagated end-user token (INV-REQ-1202 b2, API-24, CS-23):
 * [actor] is the principal, never a request field; [tenant] and [entities] are the token's tenant and entity
 * set. A token without a tenant (a platform operator) has none and no entities. Roles are not here: they are
 * re-checked in the owning service against the authority replica (CS-23).
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

    /** API-24: an entityRef inside the token's entity set. */
    fun covers(entity: EntityRef): Boolean = entity in entities
}

/**
 * Verifies the propagated end-user token (the inbound `Authorization` header) and reads its principal. The
 * owning service supplies it with its resource-server configuration over the `IdentityProvider` binding
 * (STK-27): where the tenant and entity set sit in the token is not fixed in Keel yet, so service-runtime does
 * not read claims itself. Returns `null` when the token is missing, invalid or expired (401).
 */
fun interface EndUserPrincipalResolver {
    fun resolve(authorization: String?): EndUserPrincipal?
}
