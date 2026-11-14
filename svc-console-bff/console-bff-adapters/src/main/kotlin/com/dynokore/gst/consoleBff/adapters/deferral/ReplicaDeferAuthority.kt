package com.dynokore.gst.consoleBff.adapters.deferral

import com.dynokore.gst.consoleBff.application.deferral.DeferAuthority
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Clock
import java.time.Duration

/**
 * Answers whether the user holds the scoped [permission] key on the entity, decided per request by
 * [AuthorityReplica.decide] and never cached (INV-Q-10027; OWASP ASVS V4.1.1). The key is sent unchanged: this class
 * holds no key text and no role name. Fail closed: only `allowed == true` gives true; a deny, a DEGRADED or null
 * decision and a thrown exception give false.
 */
class ReplicaDeferAuthority(
    private val replica: AuthorityReplica,
    private val clock: Clock,
    private val budget: Duration,
) : DeferAuthority {
    override fun holds(
        tenantId: String,
        principalId: String,
        entityRef: String,
        permission: String,
    ): Boolean =
        runCatching {
            replica.decide(
                AuthorizationRequest(
                    TenantId.of(tenantId),
                    Principal(principalId, PrincipalKind.HUMAN),
                    entityRef,
                    permission,
                ),
                Deadline.after(budget, clock),
            )
        }.getOrNull()?.allowed == true
}
