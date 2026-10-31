package com.dynokore.gst.evidence.retention

import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.identity.WorkloadTokenVerifier
import com.dynokore.platform.serviceruntime.identity.WorkloadVerification
import com.dynokore.platform.serviceruntime.policy.PolicyRoleResolver
import com.dynokore.platform.tenancykernel.TenantId
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

/**
 * The two ports a test stands in for, which the access service and its authority replica fill in production: the
 * end-user token (`Bearer <tenant>/<actor>`, entities [ENTITIES] granted) and the setting role of the actor. It sits
 * outside the packages the application scans, so only a test puts it in a context.
 */
@Configuration(proxyBeanMethods = false)
class RetentionBeans {
    @Bean
    @Primary
    fun retentionEndUserResolver(): EndUserPrincipalResolver =
        EndUserPrincipalResolver { authorization ->
            authorization?.removePrefix("Bearer ")?.split('/')?.takeIf { it.size == 2 }?.let { (tenant, actor) ->
                EndUserPrincipal(actor, TenantId.of(tenant), ENTITIES.map { EntityRef(it) }.toSet())
            }
        }

    @Bean
    @Primary
    fun retentionRoles(): PolicyRoleResolver = PolicyRoleResolver { _, _, _, _, _ -> setOf("tenant.admin") }

    companion object {
        val ENTITIES = listOf("ENT-A", "ENT-B")
    }
}

/** Only the workload tokens: no end-user resolver and no role resolver, as production has them today (INV-Q-12588). */
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

/** The end-user token is bound but no role resolver exists: the probe of INV-Q-12588 for the second port. */
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
