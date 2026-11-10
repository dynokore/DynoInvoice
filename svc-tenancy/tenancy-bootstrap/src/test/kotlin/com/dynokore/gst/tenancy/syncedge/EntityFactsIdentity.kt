package com.dynokore.gst.tenancy.syncedge

import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.identity.WorkloadTokenVerifier
import com.dynokore.platform.serviceruntime.identity.WorkloadVerification
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

/**
 * One end-user principal the test sets; the verifier accepts the workload tokens of `console-bff` and
 * `control-plane-svc` and the test callers `billing-svc`, `control-plane-svc-two` and `stranger-svc`
 * (verified, not mapped) only. It sits outside the packages the application scans, so only a test that lists
 * it puts it in a context.
 */
@Configuration(proxyBeanMethods = false)
class EntityFactsIdentity {
    @Bean
    @Primary
    fun factsWorkloadTokenVerifier(): WorkloadTokenVerifier =
        WorkloadTokenVerifier { token ->
            val service = token?.value?.removePrefix("token-")?.takeIf { it in VERIFIED_SERVICES }
            if (service != null) {
                WorkloadVerification.Verified("sa-$service")
            } else {
                WorkloadVerification.Refused("invalid")
            }
        }

    @Bean
    @Primary
    fun factsEndUserResolver(): EndUserPrincipalResolver =
        EndUserPrincipalResolver { authorization -> if (authorization == "Bearer $TOKEN") principal else null }

    companion object {
        const val TOKEN = "facts-token"
        private val VERIFIED_SERVICES =
            setOf("console-bff", "control-plane-svc", "billing-svc", "control-plane-svc-two", "stranger-svc")

        @Volatile
        var principal: EndUserPrincipal? = null
    }
}
