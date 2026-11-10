package com.dynokore.gst.consoleBff.adapters.session

import com.dynokore.gst.consoleBff.application.session.PrincipalStanding
import com.dynokore.gst.consoleBff.application.session.Standing
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.PrincipalKey
import com.dynokore.platform.tenancykernel.TenantId

/**
 * The live standing of a principal from the authority replica (INV-REQ-1214, INV-REQ-1201). UNKNOWN when the replica
 * lag is not within its bound (the bound of `decide`); REVOKED when the replica holds a revocation newer than the
 * authority version the session opened at; otherwise ACTIVE. No role name is read (INV-Q-10027).
 */
class ReplicaPrincipalStanding(
    private val authorityReplica: AuthorityReplica,
) : PrincipalStanding {
    override fun of(
        tenantId: String,
        principalId: String,
        authorityVersionAtOpen: Long,
    ): Standing {
        if (!authorityReplica.lag().isWithin(authorityReplica.lagBound)) return Standing.UNKNOWN
        val revocation = authorityReplica.principals.peek(PrincipalKey(TenantId.of(tenantId), principalId))
        return if (revocation != null && revocation.revocationVersion > authorityVersionAtOpen) {
            Standing.REVOKED
        } else {
            Standing.ACTIVE
        }
    }
}
