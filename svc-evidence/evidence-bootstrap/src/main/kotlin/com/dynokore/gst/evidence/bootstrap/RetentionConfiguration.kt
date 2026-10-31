package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.adapters.http.PlatformMaxRetention
import com.dynokore.gst.evidence.adapters.retention.JsonTierYearsReader
import com.dynokore.gst.evidence.application.retention.EffectiveRetention
import com.dynokore.gst.evidence.application.retention.MaxRetention
import com.dynokore.gst.evidence.application.retention.RetentionPolicy
import com.dynokore.platform.configresolver.OptionValueMaximum
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.VariationPointCatalogue
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import com.dynokore.gst.evidence.application.digest.MaxRetention as DigestMaxRetention

/**
 * The retention use cases over config-resolver's ports, which service-runtime wires (TPL-18). The default years are
 * read from the VP-43 row of the catalogue, never written in code (INV-AC-10452).
 */
@Configuration(proxyBeanMethods = false)
class RetentionConfiguration {
    @Bean
    fun effectiveRetention(
        reader: PolicyReader,
        tenants: TenantContext,
        catalogue: VariationPointCatalogue,
    ): EffectiveRetention =
        EffectiveRetention(reader, tenants, RetentionPolicy.defaultYears(catalogue), JsonTierYearsReader())

    @Bean
    fun evidenceMaxRetentionFigure(
        maximum: OptionValueMaximum,
        catalogue: VariationPointCatalogue,
    ): MaxRetention = MaxRetention(maximum, RetentionPolicy.defaultYears(catalogue))

    @Bean
    fun platformMaxRetention(
        figure: MaxRetention,
        scope: TenantScope,
    ): PlatformMaxRetention = PlatformMaxRetention(figure, scope)

    /**
     * The port of the digest consumer (INV-T-7260): the same figure `getMaxEvidenceRetention` serves, in whole years
     * rounded up. Present, it makes the fallback of [ReturnConsumerConfiguration] back off.
     */
    @Bean
    @ConditionalOnMissingBean(DigestMaxRetention::class)
    fun evidenceDigestMaxRetention(platform: PlatformMaxRetention): DigestMaxRetention =
        DigestMaxRetention { platform.readIndependently { it.period() } }
}
