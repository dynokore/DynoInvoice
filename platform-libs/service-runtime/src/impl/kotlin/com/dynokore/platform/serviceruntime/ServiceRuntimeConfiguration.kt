package com.dynokore.platform.serviceruntime

import com.dynokore.platform.serviceruntime.binding.BindingAutoConfiguration
import com.dynokore.platform.serviceruntime.diagnostics.DiagnosticsAutoConfiguration
import com.dynokore.platform.serviceruntime.health.HealthAutoConfiguration
import com.dynokore.platform.serviceruntime.logging.LoggingAutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.PropertySource

/**
 * This module's composition root (TPL-18): "L1 composition root: bindings of
 * §12.1, TransactionRunner, replicas" — this module's own share of that is the
 * bindings, health and diagnostics. `TransactionRunner` is `tenancy-kernel`'s own
 * (service-runtime uses no other L1 module, §18.9); replicas are
 * `product-runtime`'s (L3, not yet built).
 *
 * A Spring Boot `@AutoConfiguration`, so it activates on the classpath alone
 * (`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`)
 * — but every service's own `bootstrap` module still names it explicitly, the way
 * TPL-18's own template does:
 * ```
 * @SpringBootApplication(scanBasePackages = [...])
 * @Import(ServiceRuntimeConfiguration::class, ProductRuntimeConfiguration::class)
 * class SubmissionApplication
 * ```
 * so a service's own composition stays legible from its `bootstrap` module
 * without needing to know this module registers itself automatically too.
 */
@AutoConfiguration
@Import(
    BindingAutoConfiguration::class,
    HealthAutoConfiguration::class,
    DiagnosticsAutoConfiguration::class,
    LoggingAutoConfiguration::class,
)
@PropertySource("classpath:service-runtime-defaults.properties")
class ServiceRuntimeConfiguration
