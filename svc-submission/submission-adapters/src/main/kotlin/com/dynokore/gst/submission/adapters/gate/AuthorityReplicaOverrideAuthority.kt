package com.dynokore.gst.submission.adapters.gate

import com.dynokore.gst.submission.application.gate.OverrideAuthority
import com.dynokore.platform.serviceruntime.replica.AuthorityDecision
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.DecisionSource
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.serviceruntime.resilience.http.CurrentDeadline
import com.dynokore.platform.tenancykernel.TenantContext

/**
 * [OverrideAuthority] over `AuthorityReplica.decide` (INV-Q-10027, INV-T-21141). One scoped permission is decided per
 * request for the bound tenant; no role name is read. Only a LOCAL or FALLBACK decision that allows grants: a DEGRADED
 * answer, a lag beyond the bound, no replica and a revoked person all answer false (deny by default, ASVS V4.1.1).
 * An exception propagates, so nothing is read after a failed decision (fail closed).
 */
class AuthorityReplicaOverrideAuthority internal constructor(
    private val decide: (AuthorizationRequest, Deadline) -> AuthorityDecision,
    private val tenants: TenantContext,
) : OverrideAuthority {
    constructor(replica: AuthorityReplica, tenants: TenantContext) : this(replica::decide, tenants)

    override fun holds(
        userId: String,
        permission: String,
        entityRef: String,
    ): Boolean {
        val deadline = CurrentDeadline.current() ?: error("no call deadline is bound")
        val decision =
            decide(
                AuthorizationRequest(tenants.current(), Principal(userId, PrincipalKind.HUMAN), entityRef, permission),
                deadline,
            )
        return decision.allowed && decision.source in GRANTING
    }

    private companion object {
        val GRANTING = setOf(DecisionSource.LOCAL, DecisionSource.FALLBACK)
    }
}
