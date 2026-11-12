package com.dynokore.gst.ingestion.adapters.permission

import com.dynokore.gst.ingestion.application.permission.EntityPermission
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.DecisionSource
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantContext
import java.time.Clock
import java.time.Duration

/**
 * [EntityPermission] over the authority replica (INV-Q-10027), written like svc-tenancy's `ReplicaActionAuthority`:
 * true only when the replica decides allowed for the person (kind HUMAN), the caller's tenant, the entity and the
 * exact permission key, with source LOCAL. svc-ingestion binds no access-svc fallback, so a FALLBACK answer never
 * counts. With no replica bound, a DEGRADED answer or any exception the person is refused (fail closed).
 */
class ReplicaEntityPermission(
    private val replica: AuthorityReplica?,
    private val tenants: TenantContext,
    private val clock: Clock,
    private val budget: Duration,
) : EntityPermission {
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // fail closed: any failure is a refusal
    override fun holds(
        actor: String,
        entityRef: String,
        permissionKey: String,
    ): Boolean {
        val bound = replica ?: return false
        return try {
            val decision =
                bound.decide(
                    AuthorizationRequest(
                        tenants.current(),
                        Principal(actor, PrincipalKind.HUMAN),
                        entityRef,
                        permissionKey,
                    ),
                    Deadline.after(budget, clock),
                )
            decision.allowed && decision.source == DecisionSource.LOCAL
        } catch (e: Exception) {
            false
        }
    }
}
