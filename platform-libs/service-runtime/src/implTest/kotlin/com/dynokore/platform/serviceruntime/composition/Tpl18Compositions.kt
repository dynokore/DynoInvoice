package com.dynokore.platform.serviceruntime.composition

import com.dynokore.platform.productruntime.ProductRuntimeConfiguration
import com.dynokore.platform.serviceruntime.ServiceRuntimeConfiguration
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.annotation.Configurations
import org.springframework.boot.context.annotation.ImportCandidates
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
 * Like Boot, it is processed after all user configuration. ApplicationContextRunner.withConfiguration(AutoConfigurations)
 * is not used: it registers each auto-configuration as a top-level class, and so it re-registers an imported
 * ProductRuntimeConfiguration after its import. product-runtime is the stand-in [ProductRuntimeConfiguration].
 */

/** TPL-18's application class, as every service writes it. */
@Configuration(proxyBeanMethods = false)
@Import(ServiceRuntimeConfiguration::class, ProductRuntimeConfiguration::class, WithProductRuntime::class)
class Tpl18Application

@Configuration(proxyBeanMethods = false)
@Import(ProductRuntimeConfiguration::class, ServiceRuntimeConfiguration::class, WithProductRuntime::class)
class ReversedApplication

@Configuration(proxyBeanMethods = false)
@Import(WithProductRuntime::class)
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
@Import(ServiceRuntimeConfiguration::class, WithoutProductRuntime::class)
class ServiceOnlyApplication

@Configuration(proxyBeanMethods = false)
@Import(WithoutProductRuntime::class)
class ServiceOnlyAutoConfiguredApplication

/** TPL-18, on a classpath without Spring Boot's JDBC auto-configuration: no application DataSource. */
@Configuration(proxyBeanMethods = false)
@Import(ServiceRuntimeConfiguration::class, ProductRuntimeConfiguration::class, WithoutJdbc::class)
class Tpl18WithoutDataSourceApplication

class WithProductRuntime : DeferredImportSelector {
    override fun selectImports(importingClassMetadata: AnnotationMetadata): Array<String> = sorted(platform() + DataSourceAutoConfiguration::class.java + ProductRuntimeConfiguration::class.java)
}

class WithoutProductRuntime : DeferredImportSelector {
    override fun selectImports(importingClassMetadata: AnnotationMetadata): Array<String> = sorted(platform() + DataSourceAutoConfiguration::class.java)
}

class WithoutJdbc : DeferredImportSelector {
    override fun selectImports(importingClassMetadata: AnnotationMetadata): Array<String> = sorted(platform() + ProductRuntimeConfiguration::class.java)
}

/** The platform-libs auto-configurations listed in `AutoConfiguration.imports` on the classpath. */
private fun platform(): List<Class<*>> = ImportCandidates.load(AutoConfiguration::class.java, Composition::class.java.classLoader)
    .filter { it.startsWith("com.dynokore.platform.") }
    .map { Class.forName(it) }

/** In Spring Boot's auto-configuration order (@AutoConfiguration before/after). */
private fun sorted(classes: List<Class<*>>): Array<String> = Configurations.getClasses(AutoConfigurations.of(*classes.toTypedArray())).map { it.name }.toTypedArray()
