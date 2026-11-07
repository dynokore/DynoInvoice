package com.dynokore.gst.evidence.retention

import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.PrincipalState
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * Feeds the role grants of the context's one `AuthorityReplica` the way access-svc's `AuthorityChanged` events do
 * (INV-Q-8972 A), so the mounted `setPolicy` decides through `AuthorityReplica.decide` on the permission `vp-43.set`.
 * Every grant is a record of its own `grantRef`; the feed's versions are one monotonic sequence.
 */
class ReplicaGrants(
    private val replica: AuthorityReplica,
) {
    private val version = AtomicLong(0)

    /** The snapshot is complete and the head is the applied version: the replica decides locally (LOCAL). */
    fun current() {
        replica.onSnapshotComplete(version.get())
        replica.observeHead(version.get())
    }

    /** The feed is gone for longer than the lag bound: the replica is stale, the decision is the fallback's or DEGRADED. */
    fun stale() {
        replica.onDisconnected()
        Thread.sleep(replica.lagBound.toMillis() + MARGIN_MS)
    }

    /** One grant record of [principal] in [tenant]; [grantRef] names it, a later record of the same ref replaces it. */
    fun grant(
        tenant: TenantId,
        principal: String,
        grant: RoleGrant,
        grantRef: String = "${grant.role}/${grant.scope}/${grant.scopeRef}",
        state: RoleGrantState = RoleGrantState.ACTIVE,
    ) {
        replica.accept(
            AuthorityChanged(
                tenant,
                principal,
                version.incrementAndGet(),
                AuthorityRecord.RoleGrantRecord(grantRef, state, grant),
            ),
        )
        current()
    }

    /** A grant of [role] at [scope] valid from a day ago until [validUntil]. */
    fun role(
        role: String,
        scope: GrantScope,
        scopeRef: String?,
        validUntil: Instant? = null,
    ) = RoleGrant(role, scope, scopeRef, Instant.now().minusSeconds(DAY), validUntil)

    /** The principal is deleted: every earlier grant of it is void. */
    fun voidPrincipal(
        tenant: TenantId,
        principal: String,
    ) {
        replica.accept(
            AuthorityChanged(
                tenant,
                principal,
                version.incrementAndGet(),
                AuthorityRecord.PrincipalRecord(PrincipalState.DELETED),
            ),
        )
        current()
    }

    private companion object {
        const val DAY = 86_400L
        const val MARGIN_MS = 300L
    }
}
