package com.dynokore.gst.payload.bootstrap

import com.dynokore.gst.payload.adapters.persistence.JooqCapabilityRegistry
import com.dynokore.gst.payload.adapters.persistence.jooq.DefaultCatalog
import com.dynokore.gst.payload.application.CapabilityRegistry
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

    /**
     * The capability registry. `CapabilityService` (the `markSpecified` guard) is not a bean yet: the schema, table
     * and column names of the INV-REQ-480 binding table are not fixed by any published text (reported in the MR).
     */
    @Bean
    fun capabilityRegistry(dsl: DSLContext): CapabilityRegistry = JooqCapabilityRegistry(dsl)
}
