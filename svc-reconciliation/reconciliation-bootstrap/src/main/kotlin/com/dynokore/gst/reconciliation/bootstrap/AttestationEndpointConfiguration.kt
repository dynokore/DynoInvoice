package com.dynokore.gst.reconciliation.bootstrap

import com.dynokore.gst.reconciliation.adapters.http.AttestAuthority
import com.dynokore.gst.reconciliation.adapters.http.AttestationRefusalAdvice
import com.dynokore.gst.reconciliation.adapters.http.AuthorityReplicaAttestAuthority
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.tenancykernel.TenantContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The beans of the `attestWorkPaper` endpoint (INV-T-24459): the scoped-permission authority over
 * `AuthorityReplica.decide` and the one advice that renders its refusals. The `AttestWorkPaper` and `SealWorkPaper`
 * beans are those of `AttestationConfiguration` and `WorkPaperSealConfiguration`.
 */
@Configuration(proxyBeanMethods = false)
class AttestationEndpointConfiguration {
    @Bean
    fun attestAuthority(
        replica: AuthorityReplica,
        tenants: TenantContext,
    ): AttestAuthority = AuthorityReplicaAttestAuthority(replica, tenants)

    @Bean
    fun attestationRefusalAdvice(): AttestationRefusalAdvice = AttestationRefusalAdvice()
}
