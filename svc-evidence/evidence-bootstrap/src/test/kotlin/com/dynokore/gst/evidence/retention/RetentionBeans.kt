package com.dynokore.gst.evidence.retention

import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.identity.WorkloadTokenVerifier
import com.dynokore.platform.serviceruntime.identity.WorkloadVerification
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.tenancykernel.TenantId
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * The end-user token a test sends (`Bearer <tenant>/<actor>`, entities [ENTITIES] granted), which the identity provider
 * signs in production. The permission is not stood in for any more (INV-T-12601): resolving a token seeds the actor's
 * TENANT-scope `tenant.admin` grant into the production `AuthorityReplica`, so `setPolicy` is decided by
 * `AuthorityReplica.decide` for `vp-43.set` as it is in production. It sits outside the packages the application scans,
 * so only a test puts it in a context. The signed-token path is proved by `ProductionIdentityTest`.
 */
@Configuration(proxyBeanMethods = false)
class RetentionBeans {
    private val version = AtomicLong()

    @Bean
    @Primary
    fun retentionEndUserResolver(authority: ObjectProvider<AuthorityReplica>): EndUserPrincipalResolver =
        EndUserPrincipalResolver { authorization ->
            authorization?.removePrefix("Bearer ")?.split('/')?.takeIf { it.size == 2 }?.let { (tenant, actor) ->
                val tenantId = TenantId.of(tenant)
                seed(authority.getObject(), tenantId, actor)
                EndUserPrincipal(actor, tenantId, ENTITIES.map { EntityRef(it) }.toSet())
            }
        }

    private fun seed(
        replica: AuthorityReplica,
        tenant: TenantId,
        actor: String,
    ) {
        val at = version.incrementAndGet()
        val grant = RoleGrant("tenant.admin", GrantScope.TENANT, tenant.toString(), Instant.EPOCH)
        replica.accept(
            AuthorityChanged(tenant, actor, at, AuthorityRecord.RoleGrantRecord("grant-$actor", RoleGrantState.ACTIVE, grant)),
        )
        replica.onSnapshotComplete(at)
    }

    companion object {
        val ENTITIES = listOf("ENT-A", "ENT-B")
    }
}

/** Only the workload tokens: no end-user resolver of the test, so the production one is built. */
@Configuration(proxyBeanMethods = false)
class WorkloadTokensOnly {
    /** `token-<name>` is verified as subject `sa-<name>`; every other token is refused. */
    @Bean
    @Primary
    fun retentionWorkloadVerifier(): WorkloadTokenVerifier =
        WorkloadTokenVerifier { token ->
            val value = token?.value
            if (value != null && value.startsWith("token-")) {
                WorkloadVerification.Verified("sa-" + value.removePrefix("token-"))
            } else {
                WorkloadVerification.Refused("invalid")
            }
        }
}

/** The end-user token is bound; the grant of the actor is not seeded, so the permission decision refuses. */
@Configuration(proxyBeanMethods = false)
class EndUserTokensOnly {
    @Bean
    @Primary
    fun retentionEndUserResolver(): EndUserPrincipalResolver =
        EndUserPrincipalResolver { authorization ->
            authorization?.removePrefix("Bearer ")?.split('/')?.takeIf { it.size == 2 }?.let { (tenant, actor) ->
                EndUserPrincipal(actor, TenantId.of(tenant), RetentionBeans.ENTITIES.map { EntityRef(it) }.toSet())
            }
        }
}
