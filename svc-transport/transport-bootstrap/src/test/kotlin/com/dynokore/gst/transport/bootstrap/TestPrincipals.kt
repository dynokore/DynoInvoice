package com.dynokore.gst.transport.bootstrap

import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.tenancykernel.TenantId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The end-user tokens of the tests: `Authorization: Bearer <name>` resolves to the principal registered under the name,
 * anything else is unauthenticated. The principal's entity set is what the runtime reads from the authority replica.
 */
object TestPrincipals {
    private val byToken = ConcurrentHashMap<String, EndUserPrincipal>()

    val resolver = EndUserPrincipalResolver { header -> header?.removePrefix("Bearer ")?.let(byToken::get) }

    /** The actor of the principal that [authorization] resolves to. */
    fun actorOf(authorization: String): String = byToken.getValue(authorization.removePrefix("Bearer ")).actor

    /** Registers a principal of [tenant] granted [entities] and returns the Authorization header value. */
    fun register(
        tenant: UUID,
        entities: Set<String>,
        entitySetCurrent: Boolean = true,
    ): String {
        val token = "t-${UUID.randomUUID()}"
        byToken[token] =
            EndUserPrincipal(
                actor = "user:$token",
                tenant = TenantId(tenant),
                entities = entities.mapTo(mutableSetOf(), ::EntityRef),
                entitySetCurrent = entitySetCurrent,
            )
        return "Bearer $token"
    }
}
