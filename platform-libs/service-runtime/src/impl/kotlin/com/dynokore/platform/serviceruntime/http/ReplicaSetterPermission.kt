package com.dynokore.platform.serviceruntime.http

import com.dynokore.platform.configresolver.Actor
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.configresolver.SetterPermission
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.DecisionSource
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.serviceruntime.resilience.http.CurrentDeadline
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Clock
import java.time.Duration

/**
 * [SetterPermission] over `AuthorityReplica.decide` for one request (INV-Q-10027, INV-Q-10025): the permission key is
 * the request's `action` and the policy level of the call its `level`. Allowed only when `allowed` is true with source
 * LOCAL or FALLBACK; DEGRADED, a denial and an exception are not. [denied] is set when the answer was not a grant, so
 * the servlet can answer 403 SRT-004 instead of the writer's code.
 */
internal class ReplicaSetterPermission(
    private val authority: AuthorityReplica,
    private val clock: Clock,
    private val kind: PrincipalKind,
) : SetterPermission {
    @Volatile var denied: Boolean = false
        private set

    override fun holds(
        actor: Actor,
        tenant: TenantId,
        level: ScopeLevel,
        scopeRef: String?,
        entityRef: String?,
        permissionKey: String,
    ): Boolean {
        // INV-Q-17483: the entity of the call at an entity-scoped level (fail closed when absent), the empty string
        // elsewhere (the request ignores it there); scopeRef is never used in its place.
        val entity = if (level.entityScoped) entityRef?.takeIf { it.isNotEmpty() } else ""
        val granted =
            entity != null &&
                try {
                    val request =
                        AuthorizationRequest(
                            tenant,
                            Principal(actor.principal, kind),
                            entity,
                            permissionKey,
                            level = level,
                        )
                    val decision = authority.decide(request, CurrentDeadline.current() ?: Deadline.after(BUDGET, clock))
                    decision.allowed && decision.source != DecisionSource.DEGRADED
                } catch (_: RuntimeException) {
                    false
                }
        if (!granted) denied = true
        return granted
    }

    private companion object {
        val BUDGET: Duration = Duration.ofSeconds(2)
    }
}
