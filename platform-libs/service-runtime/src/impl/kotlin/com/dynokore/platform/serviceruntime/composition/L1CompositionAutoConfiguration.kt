package com.dynokore.platform.serviceruntime.composition

import com.dynokore.platform.serviceruntime.http.JobCommandHttpAutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.annotation.Import

/**
 * The L1 composition (INV-MOD-2371, INV-T-2875, INV-Q-3710): each L1 module's wiring, in dependency order, and the
 * job command HTTP surface that needs it.
 *
 * Its own auto-configuration, listed in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
 * and ordered after ServiceRuntimeConfiguration, Spring Boot's DataSourceAutoConfiguration and product-runtime's
 * ProductRuntimeConfiguration (INV-T-3881; rulings INV-Q-3877, INV-Q-3889). Its `@ConditionalOnBean`s are evaluated
 * when it is registered, and they decide on beans from those three: the application DataSource that
 * DataSourceAutoConfiguration creates from `spring.datasource.*` (TPL-16, TPL-14) and so the TransactionRunner and
 * CurrentConnection over it, and product-runtime's VariationPointCatalogue. Under TPL-18's
 * `@Import(ServiceRuntimeConfiguration::class, ProductRuntimeConfiguration::class)` both roots are user
 * configuration, registered in import order and before every auto-configuration; a wiring they imported would decide
 * before the DataSource, and with service-runtime first before the catalogue, exists. As an auto-configuration this
 * one is registered after them in every composition: TPL-18's order, the reverse order, or auto-configuration only.
 */
@AutoConfiguration(
    afterName = [
        "com.dynokore.platform.serviceruntime.ServiceRuntimeConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "com.dynokore.platform.productruntime.ProductRuntimeConfiguration",
    ],
)
@Import(
    EventLogDataSourceWiring::class,
    TenancyKernelWiring::class,
    OutboxAndQueueWiring::class,
    AuditLogWiring::class,
    ConfigResolverWiring::class,
    EvidenceStoreWiring::class,
    AdapterRuntimeWiring::class,
    CryptoWiring::class,
    RulepackKernelWiring::class,
    ServiceRuntimeWiring::class,
    JobCommandHttpAutoConfiguration::class,
)
class L1CompositionAutoConfiguration
