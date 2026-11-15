package com.dynokore.gst.canonical.bootstrap

import com.dynokore.gst.canonical.adapters.persistence.JooqDocumentVersions
import com.dynokore.gst.canonical.adapters.persistence.JooqPeriodSpans
import com.dynokore.gst.canonical.adapters.persistence.JooqProfileElections
import com.dynokore.gst.canonical.adapters.persistence.jooq.DefaultCatalog
import com.dynokore.gst.canonical.application.document.DocumentVersions
import com.dynokore.gst.canonical.application.document.PeriodSpans
import com.dynokore.gst.canonical.application.document.PurchaseElections
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
    fun canonicalSchema(): Schema = DefaultCatalog.DEFAULT_CATALOG.CANONICAL

    /** The period span store (INV-Q-21406 A); the jOOQ context is the one of the open transaction. */
    @Bean
    fun periodSpans(dsl: DSLContext): PeriodSpans = JooqPeriodSpans(dsl)

    /** The profile election store (INV-Q-21422 A); the jOOQ context is the one of the open transaction. */
    @Bean
    fun purchaseElections(dsl: DSLContext): PurchaseElections = JooqProfileElections(dsl)

    /** The document version store (INV-AC-21469); the jOOQ context is the one of the open transaction. */
    @Bean
    fun documentVersions(dsl: DSLContext): DocumentVersions = JooqDocumentVersions(dsl)
}
