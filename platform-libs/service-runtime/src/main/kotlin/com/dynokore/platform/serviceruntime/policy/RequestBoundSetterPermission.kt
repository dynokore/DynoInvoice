package com.dynokore.platform.serviceruntime.policy

import com.dynokore.platform.configresolver.Actor
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.configresolver.SetterPermission
import com.dynokore.platform.tenancykernel.TenantId

/**
 * The [SetterPermission] a service composes into its `PolicyWriter` when it supplies none of its own (INV-Q-10027):
 * the policy command servlet binds, for the duration of one request, the decision of `AuthorityReplica.decide`
 * (allowed only for source LOCAL or FALLBACK, INV-Q-10025). Outside a bound request it answers by throwing, which
 * the writer treats as POL-004 (fail closed).
 */
class RequestBoundSetterPermission : SetterPermission {
    override fun holds(
        actor: Actor,
        tenant: TenantId,
        level: ScopeLevel,
        scopeRef: String?,
        entityRef: String?,
        permissionKey: String,
    ): Boolean =
        (bound.get() ?: error("no setter permission is bound to this request"))
            .holds(actor, tenant, level, scopeRef, entityRef, permissionKey)

    companion object {
        private val bound = ThreadLocal<SetterPermission?>()

        /** Runs [block] with [permission] answering for [RequestBoundSetterPermission] on this thread only. */
        fun <T> bind(
            permission: SetterPermission,
            block: () -> T,
        ): T {
            val before = bound.get()
            bound.set(permission)
            try {
                return block()
            } finally {
                bound.set(before)
            }
        }
    }
}
