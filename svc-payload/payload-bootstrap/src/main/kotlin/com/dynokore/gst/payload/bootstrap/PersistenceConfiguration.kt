package com.dynokore.gst.payload.bootstrap

import com.dynokore.gst.payload.adapters.persistence.JooqCapabilityRegistry
import com.dynokore.gst.payload.adapters.persistence.jooq.DefaultCatalog
import com.dynokore.gst.payload.application.AbsentMdePackSource
import com.dynokore.gst.payload.application.CapabilityRegistry
import com.dynokore.gst.payload.application.CapabilityService
import com.dynokore.gst.payload.application.MdePackSource
import org.jooq.DSLContext
import org.jooq.Schema
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The service's generated jOOQ schema as a bean (service-runtime README, "Schema supply", INV-Q-4815): the tenant
 * predicate wiring classifies every table of it at start-up.
 */
@Configuration(proxyBeanMethods = false)
class PersistenceConfiguration {
    @Bean
    fun payloadSchema(): Schema = DefaultCatalog.DEFAULT_CATALOG.PAYLOAD

    @Bean
    fun capabilityRegistry(dsl: DSLContext): CapabilityRegistry = JooqCapabilityRegistry(dsl)

    /**
     * The production source: no MDE pack is loaded yet, so `markSpecified(SERIALISATION)` stays refused. The task that
     * loads the pack replaces this one bean.
     */
    @Bean
    fun mdePackSource(): MdePackSource = AbsentMdePackSource()

    @Bean
    fun capabilityService(
        registry: CapabilityRegistry,
        packs: MdePackSource,
    ): CapabilityService = CapabilityService(registry, packs)
}
