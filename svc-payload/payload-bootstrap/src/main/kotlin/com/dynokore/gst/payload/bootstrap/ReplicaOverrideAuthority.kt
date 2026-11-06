package com.dynokore.gst.payload.bootstrap

import com.dynokore.gst.payload.application.override.OverrideAuthority
import com.dynokore.platform.serviceruntime.replica.AuthorityDecision
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.DecisionSource
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.serviceruntime.resilience.http.CurrentDeadline
import com.dynokore.platform.tenancykernel.TenantContext
import java.time.Clock
import java.time.Duration

/**
 * INV-Q-17175 A, INV-Q-10027: [OverrideAuthority] over `AuthorityReplica.decide`. A scoped permission key is held only
 * when the decision allows it and its source is LOCAL or FALLBACK; a DEGRADED source, a denial, a revoked person and a
 * lag beyond the bound with no fallback each answer false (fail closed). No role name is read here.
 */
class ReplicaOverrideAuthority(
    private val decide: (AuthorizationRequest, Deadline) -> AuthorityDecision,
    private val tenants: TenantContext,
    private val clock: Clock,
) : OverrideAuthority {
    constructor(replica: AuthorityReplica, tenants: TenantContext, clock: Clock) :
        this(replica::decide, tenants, clock)

    override fun holds(
        userId: String,
        permission: String,
        entityRef: String,
    ): Boolean {
        val request =
            AuthorizationRequest(
                tenant = tenants.current(),
                principal = Principal(userId, PrincipalKind.HUMAN),
                entityRef = entityRef,
                action = permission,
            )
        val deadline = CurrentDeadline.current() ?: Deadline.after(FALLBACK_TIMEOUT, clock)
        val decision = decide(request, deadline)
        return decision.allowed && decision.source in TRUSTED_SOURCES
    }

    private companion object {
        val TRUSTED_SOURCES = setOf(DecisionSource.LOCAL, DecisionSource.FALLBACK)
        val FALLBACK_TIMEOUT: Duration = Duration.ofMillis(500)
    }
}
