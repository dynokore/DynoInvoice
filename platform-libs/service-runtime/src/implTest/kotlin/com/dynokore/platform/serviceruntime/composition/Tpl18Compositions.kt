package com.dynokore.platform.serviceruntime.composition

import com.dynokore.platform.serviceruntime.ServiceRuntimeConfiguration
import com.dynokore.platform.tenancyjooq.TenancyProbeAutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.annotation.Configurations
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DeferredImportSelector
import org.springframework.context.annotation.Import
import org.springframework.core.type.AnnotationMetadata

/*
 * Service application classes composed as TPL-18 writes them (INV-T-3881; rulings INV-Q-3877, INV-Q-3889): the
 * `@Import` of the two composition roots, plus `@SpringBootApplication`'s auto-configuration. A DeferredImportSelector
 * stands in for that auto-configuration. It lists every platform-libs auto-configuration that the classpath's
 * `AutoConfiguration.imports` names, plus Spring Boot's DataSourceAutoConfiguration, sorted as Spring Boot sorts them.
 * Like Boot, it is processed after all user configuration.
 * ApplicationContextRunner.withConfiguration(AutoConfigurations)
 * is not used: it registers each auto-configuration as a top-level class, and so it re-registers an imported
 * StandInProductRuntimeConfiguration after its import. product-runtime is the stand-in [StandInProductRuntimeConfiguration].
 */

/** TPL-18's application class, as every service writes it. */
@Configuration(proxyBeanMethods = false)
@Import(
    ServiceRuntimeConfiguration::class,
    StandInProductRuntimeConfiguration::class,
    WithProductRuntime::class,
    DefaultServiceSchema::class,
)
class Tpl18Application

@Configuration(proxyBeanMethods = false)
@Import(
    StandInProductRuntimeConfiguration::class,
    ServiceRuntimeConfiguration::class,
    WithProductRuntime::class,
    DefaultServiceSchema::class,
)
class ReversedApplication

/**
 * Auto-configuration only: the service imports neither root, and `@Import(WithProductRuntime)` stands in for
 * `@SpringBootApplication`'s auto-configuration. product-runtime is the stand-in, so this proves the pool, port and
 * servlet wiring and not the ordering by the real class name, which product-runtime's implTest proves with the real
 * class (INV-T-4747, coordinator rulings INV-Q-4773, INV-Q-4775).
 */
@Configuration(proxyBeanMethods = false)
@Import(WithProductRuntime::class, DefaultServiceSchema::class)
class AutoConfiguredApplication

/** The three ways a service registers the two roots. */
enum class Composition(
    val application: Class<*>,
) {
    TPL_18(Tpl18Application::class.java),
    REVERSED_IMPORT(ReversedApplication::class.java),
    AUTO_CONFIGURATION_ONLY(AutoConfiguredApplication::class.java),
}

/** A service without product-runtime: no catalogue. */
@Configuration(proxyBeanMethods = false)
@Import(ServiceRuntimeConfiguration::class, WithoutProductRuntime::class, DefaultServiceSchema::class)
class ServiceOnlyApplication

@Configuration(proxyBeanMethods = false)
@Import(WithoutProductRuntime::class, DefaultServiceSchema::class)
class ServiceOnlyAutoConfiguredApplication

/** TPL-18, on a classpath without Spring Boot's JDBC auto-configuration: no application DataSource. */
@Configuration(proxyBeanMethods = false)
@Import(ServiceRuntimeConfiguration::class, StandInProductRuntimeConfiguration::class, WithoutJdbc::class)
class Tpl18WithoutDataSourceApplication

class WithProductRuntime : DeferredImportSelector {
    override fun selectImports(importingClassMetadata: AnnotationMetadata): Array<String> =
        sorted(platform() + DataSourceAutoConfiguration::class.java + StandInProductRuntimeConfiguration::class.java)
}

class WithoutProductRuntime : DeferredImportSelector {
    override fun selectImports(importingClassMetadata: AnnotationMetadata): Array<String> =
        sorted(platform() + DataSourceAutoConfiguration::class.java)
}

class WithoutJdbc : DeferredImportSelector {
    override fun selectImports(importingClassMetadata: AnnotationMetadata): Array<String> =
        sorted(platform() + StandInProductRuntimeConfiguration::class.java)
}

/**
 * The platform-libs auto-configurations a service's classpath names in `AutoConfiguration.imports`, as class literals
 * (no class is loaded by name). [PlatformAutoConfigurationListTest] fails when this list and the imports on the
 * classpath differ by one name (owner ruling INV-Q-4384 (a), coordinator ruling INV-Q-4786).
 */
internal fun platform(): List<Class<*>> =
    listOf(
        ServiceRuntimeConfiguration::class.java,
        L1CompositionAutoConfiguration::class.java,
        TenancyJooqWiring::class.java,
        TenancyProbeAutoConfiguration::class.java,
    )

/** In Spring Boot's auto-configuration order (@AutoConfiguration before/after). */
internal fun sorted(classes: List<Class<*>>): Array<String> =
    Configurations
        .getClasses(AutoConfigurations.of(*classes.toTypedArray()))
        .map {
            it.name
        }.toTypedArray()
