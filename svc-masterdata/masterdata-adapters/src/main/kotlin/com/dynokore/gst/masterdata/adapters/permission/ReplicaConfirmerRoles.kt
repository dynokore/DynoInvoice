package com.dynokore.gst.masterdata.adapters.permission

import com.dynokore.gst.masterdata.application.identity.ConfirmerRoles
import com.dynokore.gst.masterdata.domain.identity.ConfirmerRole
import com.dynokore.gst.masterdata.domain.identity.EntityRef
import com.dynokore.gst.masterdata.domain.identity.PersonRef
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantContext
import java.time.Clock
import java.time.Duration

/**
 * [ConfirmerRoles] over the public holder query of the authority replica (service-runtime 12.0.0 removed
 * `PolicyRoleResolver` and keeps no public role answer, INV-Q-10107 C): the subject holds a confirmer role at the
 * entity when the replica lists the subject among the holders of that role on the entity. Fail closed (INV-Q-8121 A,
 * CS-23, API-24): no replica, a replica beyond its lag bound (the query throws) and any exception give the empty set,
 * so nobody can confirm.
 */
class ReplicaConfirmerRoles(
    private val replica: AuthorityReplica?,
    private val tenants: TenantContext,
    private val clock: Clock,
) : ConfirmerRoles {
    override fun rolesOf(
        subject: PersonRef,
        entity: EntityRef,
    ): Set<String> {
        val bound = replica ?: return emptySet()
        return try {
            val tenant = tenants.current()
            val deadline = Deadline.after(Duration.ofSeconds(1), clock)
            ConfirmerRole.entries
                .filter { subject.value in bound.holdersOn(tenant, it.wire, entity.value, deadline) }
                .map { it.wire }
                .toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }
}
