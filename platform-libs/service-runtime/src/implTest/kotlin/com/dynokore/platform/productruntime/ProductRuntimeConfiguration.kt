package com.dynokore.platform.productruntime

import com.dynokore.platform.configresolver.DelegationProbe
import com.dynokore.platform.configresolver.VariationPointCatalogue
import com.dynokore.platform.configresolver.VariationPointDefinition
import com.dynokore.platform.configresolver.VariationPointKey
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A stand-in for product-runtime's composition root (L3, INV-T-2876), under its real name so that ordering by name
 * applies to it (INV-T-3881): like the real one it is an auto-configuration ordered before ServiceRuntimeConfiguration
 * and provides the VariationPointCatalogue and a DelegationProbe. service-runtime does not depend on product-runtime,
 * so its tests compose this stand-in the way TPL-18 composes the real one.
 */
@AutoConfiguration(beforeName = ["com.dynokore.platform.serviceruntime.ServiceRuntimeConfiguration"])
class ProductRuntimeConfiguration {
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
