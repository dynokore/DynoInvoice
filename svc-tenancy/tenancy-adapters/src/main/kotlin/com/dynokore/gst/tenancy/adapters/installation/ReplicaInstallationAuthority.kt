package com.dynokore.gst.tenancy.adapters.installation

import com.dynokore.gst.tenancy.application.installation.InstallationAuthority
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.DecisionSource
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Clock
import java.time.Duration

/**
 * [InstallationAuthority] over the authority replica (INV-Q-10027): `decide` at [ScopeLevel.INSTALLATION] under
 * [TenantId.PLATFORM], answered from the local replica. With no replica bound, or a DEGRADED or FALLBACK answer, the
 * person is refused (fail closed). The tenant bound by the request is not read.
 */
class ReplicaInstallationAuthority(
    private val replica: AuthorityReplica?,
    private val clock: Clock = Clock.systemUTC(),
    private val budget: Duration = Duration.ofSeconds(2),
) : InstallationAuthority {
    override fun holds(
        person: String,
        permission: String,
    ): Boolean {
        val decision =
            replica?.decide(
                AuthorizationRequest(
                    TenantId.PLATFORM,
                    Principal(person, PrincipalKind.HUMAN),
                    "",
                    permission,
                    level = ScopeLevel.INSTALLATION,
                ),
                Deadline.after(budget, clock),
            ) ?: return false
        return decision.allowed && decision.source == DecisionSource.LOCAL
    }
}
