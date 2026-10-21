package com.dynokore.gst.canonical.bootstrap

import com.dynokore.gst.canonical.adapters.persistence.jooq.DefaultCatalog
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
    fun canonicalSchema(): Schema = DefaultCatalog.DEFAULT_CATALOG.CANONICAL
}
