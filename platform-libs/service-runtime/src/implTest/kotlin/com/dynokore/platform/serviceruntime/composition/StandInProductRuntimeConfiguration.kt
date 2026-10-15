package com.dynokore.platform.serviceruntime.composition

import com.dynokore.platform.configresolver.DelegationProbe
import com.dynokore.platform.configresolver.VariationPointCatalogue
import com.dynokore.platform.configresolver.VariationPointDefinition
import com.dynokore.platform.configresolver.VariationPointKey
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A stand-in for product-runtime's composition root (L3, INV-T-2876), in service-runtime's own package: ARC-16 lets an
 * L1 module neither import nor declare an L3 package (INV-Q-4734). Like the real one it is an auto-configuration ordered
 * before ServiceRuntimeConfiguration and provides the VariationPointCatalogue and a DelegationProbe, so service-runtime's
 * tests compose it the way TPL-18 composes the real one. It does not carry the real class name, so the cases that depend
 * on ordering by that name (INV-T-3881's auto-configuration-only order, `afterName` of L1CompositionAutoConfiguration,
 * INV-Q-3877) run in product-runtime's implTest with the real ProductRuntimeConfiguration.
 */
@AutoConfiguration(beforeName = ["com.dynokore.platform.serviceruntime.ServiceRuntimeConfiguration"])
class StandInProductRuntimeConfiguration {
    @Bean
    fun gstVariationPointCatalogue(): RecordingCatalogue = RecordingCatalogue()

    @Bean
    @ConditionalOnProperty(name = [DELEGATION_PROBE], matchIfMissing = true)
    fun delegationProbe(): DelegationProbe = DelegationProbe { _, _ -> null }

    companion object {
        /** `false` leaves the optional DelegationProbe out. */
        const val DELEGATION_PROBE = "stand-in.product-runtime.delegation-probe"
    }
}

/** A catalogue with no rows that records every key it is asked for. */
class RecordingCatalogue : VariationPointCatalogue {
    val asked: MutableList<VariationPointKey> = CopyOnWriteArrayList()

    override fun find(key: VariationPointKey): VariationPointDefinition? {
        asked += key
        return null
    }
}
