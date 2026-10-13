package com.dynokore.platform.serviceruntime.identity

import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.tenancykernel.TenantId

/**
 * The caller of an internal operation, from the propagated end-user token (INV-REQ-1202 b2 and b4, API-24,
 * CS-23; owner ruling INV-Q-3716 A): [actor] is the token's `sub` and [tenant] its `tenant_id`, never a request
 * field; [entities] are the principal's granted entities, read from the authority replica by the service's
 * resolver, not from the token. A token without a tenant (a platform operator) has none and no entities. Roles
 * are not here: they are re-checked in the owning service against the authority replica (CS-23).
 *
 * [entitySetCurrent] is false while the authority replica is beyond its lag bound or has received no version yet
 * (coordinator ruling INV-Q-3771): [entities] is then not to be trusted, and an entityRef check goes to the
 * authority decision with the operation's own permission instead (INV-Q-3782), never refused for an empty set.
 *
 * [operatorSession] is set only for a tenant-scoped operator session token issued by access-svc (owner ruling
 * INV-Q-4119, INV-REQ-862 b4, INV-REQ-1202 b4): the operator is the [actor] and the session's tenant the
 * [tenant], bound like any tenant principal; it always has a tenant.
 */
data class EndUserPrincipal(
    val actor: String,
    val tenant: TenantId?,
    val entities: Set<EntityRef>,
    val entitySetCurrent: Boolean = true,
    val operatorSession: OperatorSession? = null,
) {
    init {
        require(actor.isNotBlank()) { "actor must not be blank" }
        require(tenant != null || entities.isEmpty()) { "an entity set belongs to a tenant" }
        require(tenant != null || operatorSession == null) { "an operator session is scoped to one tenant" }
        // INV-REQ-1226 b7: the reserved platform tenant is never bound from a token.
        require(tenant == null || !tenant.isPlatform) { "a token never carries the reserved platform tenant" }
    }

    /** API-24: an entityRef inside the principal's granted entities. */
    fun covers(entity: EntityRef): Boolean = entity in entities
}

/**
 * The `act` claim of a tenant-scoped operator session token (owner ruling INV-Q-4119; platform-contracts
 * access `openOperatorSession`): the operator's platform [role] and the support [case] reference.
 */
data class OperatorSession(
    val role: String,
    val case: String,
) {
    init {
        require(role.isNotBlank()) { "operator role must not be blank" }
        require(case.isNotBlank()) { "case reference must not be blank" }
    }
}

/**
 * Verifies the propagated end-user token (the inbound `Authorization` header) and reads its principal. The
 * owning service supplies it with its resource-server configuration over the `IdentityProvider` binding
 * (STK-27). Layout per owner ruling INV-Q-3716 A (INV-REQ-1202 b4): the actor is the token's `sub`, the tenant
 * its `tenant_id`, and the entity set the principal's granted entities read from the authority replica; the
 * default resolver ships with INV-T-3335. Returns `null` when the token is missing, invalid or expired (401).
 * Throws [TokenIssuerUnavailableException] when the issuer's keys cannot be obtained and no cached key verifies
 * the token (503 SRT-011, coordinator ruling INV-Q-3773): the token may be valid, so it is never a 401.
 */
fun interface EndUserPrincipalResolver {
    fun resolve(authorization: String?): EndUserPrincipal?
}

/** The token issuer's keys cannot be obtained and no cached key verifies the token (INV-Q-3773). Retryable. */
class TokenIssuerUnavailableException(cause: Throwable? = null) : RuntimeException(MESSAGE, cause) {
    private companion object {
        const val MESSAGE = "the token issuer is unavailable, so the token cannot be verified now"
    }
}
