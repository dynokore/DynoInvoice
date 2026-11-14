package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.gate.HttpReleaseDecisions
import com.dynokore.gst.submission.adapters.gate.PolicyReplicaGateModes
import com.dynokore.gst.submission.adapters.http.FilingGateOverrides
import com.dynokore.gst.submission.adapters.persistence.JooqFilingGateAuditTrail
import com.dynokore.gst.submission.application.gate.ApproveOverride
import com.dynokore.gst.submission.application.gate.AuditTrail
import com.dynokore.gst.submission.application.gate.GateModes
import com.dynokore.gst.submission.application.gate.LatestChecks
import com.dynokore.gst.submission.application.gate.OverrideAuthority
import com.dynokore.gst.submission.application.gate.OverrideStore
import com.dynokore.gst.submission.application.gate.ReadOverrides
import com.dynokore.gst.submission.application.gate.ReleaseDecisions
import com.dynokore.gst.submission.application.gate.RequestOverride
import com.dynokore.gst.submission.domain.gate.ReleaseOutcome
import com.dynokore.platform.serviceruntime.replica.PolicyReplica
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Clock
import java.time.Duration

/**
 * TPL-18 wiring of the filing-gate override (INV-Q-10691 A, INV-Q-17042 A): the production adapters of the ports of
 * `submission-application` and the three use cases. The beans of `OverrideStore`, `LatestChecks`, `OverrideAuthority`
 * and `ReadinessCheckReader` are main's (`OverrideConfiguration`, `FilingConfiguration`,
 * `LatestCheckConfiguration`). The permission is decided per request by `AuthorityReplica.decide` (INV-Q-10027); the mode VP-14 is read from the policy replica at the request time.
 */
@Configuration(proxyBeanMethods = false)
class GateConfiguration {
    @Bean
    fun filingGateAuditTrail(
        dsl: DSLContext,
        tenants: TenantContext,
    ): JooqFilingGateAuditTrail = JooqFilingGateAuditTrail(dsl, tenants)

    @Bean
    fun gateModes(
        policy: PolicyReplica,
        tenants: TenantContext,
        clock: Clock,
    ): GateModes = PolicyReplicaGateModes(policy, tenants, clock)

    /**
     * `decideOverrideRelease` of access-svc through the generated client, with the credentials and deadline of the
     * `internalRestClientBuilder`. With no access-svc URL configured every decision is a denial (fail closed).
     */
    @Bean
    fun releaseDecisions(
        @Qualifier("internalRestClientBuilder") builder: RestClient.Builder,
        environment: Environment,
    ): ReleaseDecisions =
        environment.getProperty(ACCESS_URL_PROPERTY)?.takeIf { it.isNotBlank() }?.let { url ->
            HttpReleaseDecisions(
                builder
                    .baseUrl(url)
                    .requestFactory(
                        JdkClientHttpRequestFactory(
                            HttpClient
                                .newBuilder()
                                .version(HttpClient.Version.HTTP_1_1)
                                .connectTimeout(DECISION_TIMEOUT)
                                .build(),
                        ).apply { setReadTimeout(DECISION_TIMEOUT) },
                    ).build(),
            )
        } ?: ReleaseDecisions { _, _, _, _ -> ReleaseOutcome.Denied(ReleaseOutcome.NOT_OVERRIDABLE) }

    @Bean
    @Suppress("LongParameterList")
    fun requestOverride(
        authority: OverrideAuthority,
        modes: GateModes,
        checks: LatestChecks,
        decisions: ReleaseDecisions,
        store: OverrideStore,
        audit: AuditTrail,
    ): RequestOverride = RequestOverride(authority, modes, checks, decisions, store, audit)

    @Bean
    fun approveOverride(
        authority: OverrideAuthority,
        checks: LatestChecks,
        decisions: ReleaseDecisions,
        store: OverrideStore,
        audit: AuditTrail,
    ): ApproveOverride = ApproveOverride(authority, checks, decisions, store, audit)

    @Bean
    fun readOverrides(
        authority: OverrideAuthority,
        checks: LatestChecks,
        store: OverrideStore,
    ): ReadOverrides = ReadOverrides(authority, checks, store)

    @Bean
    @Suppress("LongParameterList")
    fun filingGateOverrides(
        request: RequestOverride,
        approve: ApproveOverride,
        read: ReadOverrides,
        store: OverrideStore,
        checks: LatestChecks,
        audit: JooqFilingGateAuditTrail,
    ): FilingGateOverrides = FilingGateOverrides(request, approve, read, store, checks, audit)

    private companion object {
        const val ACCESS_URL_PROPERTY = "product-runtime.fallback.access-svc-url"

        /** `x-timeout` of `decideOverrideRelease` in the access contract. */
        val DECISION_TIMEOUT: Duration = Duration.ofMillis(500)
    }
}
