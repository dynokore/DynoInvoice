package com.dynokore.gst.transport.bootstrap

import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.tenancykernel.TenantId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The end-user tokens of the tests: `Authorization: Bearer <name>` resolves to the principal registered under the name,
 * anything else is unauthenticated. No entity set unless a test names one: the runtime checks the body entity refs of
 * the operations marked `x-entity-ref` against the set of the principal (API-24).
 */
object TestPrincipals {
    private val byToken = ConcurrentHashMap<String, EndUserPrincipal>()

    val resolver = EndUserPrincipalResolver { header -> header?.removePrefix("Bearer ")?.let(byToken::get) }

    /** The actor of the principal that [authorization] resolves to. */
    fun actorOf(authorization: String): String = byToken.getValue(authorization.removePrefix("Bearer ")).actor

    /** Registers a principal of [tenant] (null: no `tenant_id`) and returns the Authorization header value. */
    fun register(
        tenant: UUID?,
        entities: Set<String> = emptySet(),
    ): String {
        val token = "t-${UUID.randomUUID()}"
        byToken[token] =
            EndUserPrincipal(
                actor = "user:$token",
                tenant = tenant?.let(::TenantId),
                entities = entities.map(::EntityRef).toSet(),
            )
        return "Bearer $token"
    }
}
