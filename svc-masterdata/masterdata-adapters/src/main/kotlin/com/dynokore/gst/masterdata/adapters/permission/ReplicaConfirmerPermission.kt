package com.dynokore.gst.masterdata.adapters.permission

import com.dynokore.gst.masterdata.application.identity.ConfirmerDecision
import com.dynokore.gst.masterdata.application.identity.ConfirmerPermission
import com.dynokore.gst.masterdata.domain.identity.EntityRef
import com.dynokore.gst.masterdata.domain.identity.PersonRef
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
 * [ConfirmerPermission] over `AuthorityReplica.decide` (INV-Q-10027, INV-Q-8121): the person (kind HUMAN) is asked the
 * action [ACTION] at the entity. The answer is `allowed` only when the decision allows with source LOCAL or FALLBACK
 * (INV-Q-10025); DEGRADED, a denial, an exception and no replica give `allowed` false (fail closed, CS-23, API-24).
 */
class ReplicaConfirmerPermission(
    private val replica: AuthorityReplica?,
    private val tenants: TenantContext,
    private val clock: Clock,
) : ConfirmerPermission {
    override fun decide(
        subject: PersonRef,
        entity: EntityRef,
    ): ConfirmerDecision {
        val bound = replica ?: return DENIED
        return try {
            val request =
                AuthorizationRequest(
                    tenants.current(),
                    Principal(subject.value, PrincipalKind.HUMAN),
                    entity.value,
                    ACTION,
                )
            val decision = bound.decide(request, Deadline.after(Duration.ofSeconds(1), clock))
            ConfirmerDecision(decision.allowed && decision.source != DecisionSource.DEGRADED, decision.source)
        } catch (_: Exception) {
            DENIED
        }
    }

    companion object {
        /** The permission key of the second confirmation (gst-catalogue `entity-config.confirm`). */
        const val ACTION = "entity-config.confirm"
        private val DENIED = ConfirmerDecision(false, DecisionSource.DEGRADED)
    }
}
