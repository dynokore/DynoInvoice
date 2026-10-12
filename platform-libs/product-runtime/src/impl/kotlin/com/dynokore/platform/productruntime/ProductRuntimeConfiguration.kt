package com.dynokore.platform.productruntime

import com.dynokore.gst.productRuntimeAccessClient.adapters.clients.api.AuthoriseApi
import com.dynokore.gst.productRuntimeTenancyClient.adapters.clients.api.EntitiesApi
import com.dynokore.platform.configresolver.DelegationProbe
import com.dynokore.platform.configresolver.VariationPointCatalogue
import com.dynokore.platform.productruntime.catalogue.GstVariationPointCatalogue
import com.dynokore.platform.productruntime.delegation.AuthorityDelegationProbe
import com.dynokore.platform.productruntime.fallback.AccessAuthorityFallback
import com.dynokore.platform.productruntime.fallback.TenancyStructureFallback
import com.dynokore.platform.productruntime.feed.AuthorityFeedHandler
import com.dynokore.platform.productruntime.feed.PolicyFeed
import com.dynokore.platform.productruntime.feed.StructureFeedHandler
import com.dynokore.platform.serviceruntime.replica.AuthorityFallback
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.PolicyReplica
import com.dynokore.platform.serviceruntime.replica.StructureFallback
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment
import org.springframework.web.client.RestClient

/**
 * Where the two fallback services answer (INV-REQ-1200 b2, INV-REQ-1201 b3). Without a URL the replica has no
 * fallback and serves what it holds; the structure replica keeps serving its last state, the authority replica
 * applies its degraded rule (INV-REQ-1214).
 */
@ConfigurationProperties(prefix = "product-runtime.fallback")
data class ProductRuntimeProperties(
    val tenancySvcUrl: String? = null,
    val accessSvcUrl: String? = null,
)

/**
 * product-runtime's composition (TPL-18, INV-MOD-2745): the gst-catalogue VariationPoint binding and the
 * DelegationProbe config-resolver resolves through (INV-Q-3119, INV-Q-3136), the tenancy-svc and access-svc
 * fallbacks service-runtime guards with each edge's policy (AD-26), and the structure, authority and policy feeds.
 *
 * A service imports it after `ServiceRuntimeConfiguration` as TPL-18 writes it, or gets it from the auto-configuration
 * listing; it relies on no ordering. service-runtime takes the catalogue whatever the order (INV-T-3881, coordinator
 * ruling INV-Q-3877). The feeds take the replicas service-runtime defines.
 */
@AutoConfiguration
@EnableConfigurationProperties(ProductRuntimeProperties::class)
class ProductRuntimeConfiguration {
    @Bean
    @ConditionalOnMissingBean(VariationPointCatalogue::class)
    fun gstVariationPointCatalogue(environment: Environment): GstVariationPointCatalogue = GstVariationPointCatalogue(environment.getProperty("gst.service"))

    @Bean
    @ConditionalOnMissingBean(DelegationProbe::class)
    fun authorityDelegationProbe(authority: ObjectProvider<AuthorityReplica>): DelegationProbe = AuthorityDelegationProbe { authority.ifAvailable }

    @Bean
    @ConditionalOnMissingBean(StructureFallback::class)
    @ConditionalOnProperty(prefix = "product-runtime.fallback", name = ["tenancy-svc-url"])
    fun tenancyStructureFallback(
        @Qualifier("internalRestClientBuilder") builder: ObjectProvider<RestClient.Builder>,
        properties: ProductRuntimeProperties,
    ): StructureFallback = TenancyStructureFallback(
        EntitiesApi(builder.getObject().baseUrl(requireNotNull(properties.tenancySvcUrl)).build()),
    )

    @Bean
    @ConditionalOnMissingBean(AuthorityFallback::class)
    @ConditionalOnProperty(prefix = "product-runtime.fallback", name = ["access-svc-url"])
    fun accessAuthorityFallback(
        @Qualifier("internalRestClientBuilder") builder: ObjectProvider<RestClient.Builder>,
        properties: ProductRuntimeProperties,
    ): AuthorityFallback = AccessAuthorityFallback(
        AuthoriseApi(builder.getObject().baseUrl(requireNotNull(properties.accessSvcUrl)).build()),
    )

    @Bean
    fun structureFeedHandler(replica: StructureReplica): StructureFeedHandler = StructureFeedHandler(replica)

    @Bean
    fun authorityFeedHandler(replica: AuthorityReplica): AuthorityFeedHandler = AuthorityFeedHandler(replica)

    @Bean
    fun policyFeed(replica: PolicyReplica): PolicyFeed = PolicyFeed(replica)
}
