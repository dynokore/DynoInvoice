package com.dynokore.platform.serviceruntime.datasource

import com.dynokore.platform.productruntime.ProductRuntimeConfiguration
import com.dynokore.platform.serviceruntime.ServiceRuntimeConfiguration
import com.dynokore.platform.serviceruntime.composition.platform
import com.dynokore.platform.serviceruntime.composition.sorted
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DeferredImportSelector
import org.springframework.context.annotation.Import
import org.springframework.core.type.AnnotationMetadata

/*
 * INV-T-3913 (P109): a service composed as TPL-18 writes it (`@Import(ServiceRuntimeConfiguration::class,
 * ProductRuntimeConfiguration::class)`), with Spring Boot's DataSourceAutoConfiguration and FlywayAutoConfiguration
 * among its auto-configurations, sorted as Spring Boot sorts them (see composition/Tpl18Compositions.kt).
 */

@Configuration(proxyBeanMethods = false)
@Import(ServiceRuntimeConfiguration::class, ProductRuntimeConfiguration::class, WithJdbcAndFlyway::class)
class Tpl18JdbcApplication

class WithJdbcAndFlyway : DeferredImportSelector {
    override fun selectImports(importingClassMetadata: AnnotationMetadata): Array<String> = sorted(
        platform() + DataSourceAutoConfiguration::class.java + FlywayAutoConfiguration::class.java +
            ProductRuntimeConfiguration::class.java,
    )
}

/** The TPL-16 properties every service sets, over [Tpl18JdbcApplication]. */
fun tpl18Service(): ApplicationContextRunner = ApplicationContextRunner()
    .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
    .withUserConfiguration(Tpl18JdbcApplication::class.java)
    .withPropertyValues(
        "service-runtime.workload-identity.audience=submission-svc",
        "gst.service=submission-svc",
        "gst.instance-id=submission-svc-0",
        "spring.flyway.enabled=false",
    )
