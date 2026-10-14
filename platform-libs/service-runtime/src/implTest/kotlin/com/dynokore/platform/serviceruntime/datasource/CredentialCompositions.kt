package com.dynokore.platform.serviceruntime.datasource

import com.dynokore.platform.productruntime.ProductRuntimeConfiguration
import com.dynokore.platform.serviceruntime.ServiceRuntimeConfiguration
import com.dynokore.platform.serviceruntime.composition.platform
import com.dynokore.platform.serviceruntime.composition.sorted
import com.zaxxer.hikari.HikariDataSource
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DeferredImportSelector
import org.springframework.context.annotation.Import
import org.springframework.core.type.AnnotationMetadata
import java.nio.file.Files
import java.nio.file.Path
import javax.sql.DataSource

/*
 * INV-T-3913 (P109): a service composed as TPL-18 writes it (`@Import(ServiceRuntimeConfiguration::class,
 * ProductRuntimeConfiguration::class)`), with Spring Boot's DataSourceAutoConfiguration and FlywayAutoConfiguration
 * among its auto-configurations, sorted as Spring Boot sorts them (see composition/Tpl18Compositions.kt).
 */

@Configuration(proxyBeanMethods = false)
@Import(ServiceRuntimeConfiguration::class, ProductRuntimeConfiguration::class, WithJdbcAndFlyway::class)
class Tpl18JdbcApplication

class WithJdbcAndFlyway : DeferredImportSelector {
    override fun selectImports(importingClassMetadata: AnnotationMetadata): Array<String> =
        sorted(
            platform() + DataSourceAutoConfiguration::class.java + FlywayAutoConfiguration::class.java +
                ProductRuntimeConfiguration::class.java,
        )
}

/** The TPL-16 properties every service sets, over [Tpl18JdbcApplication]. */
fun tpl18Service(): ApplicationContextRunner =
    ApplicationContextRunner()
        .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
        .withUserConfiguration(Tpl18JdbcApplication::class.java)
        .withPropertyValues(
            "service-runtime.workload-identity.audience=submission-svc",
            "gst.service=submission-svc",
            "gst.instance-id=submission-svc-0",
            "service-runtime.pool.profile=minimal",
            "spring.flyway.enabled=false",
        )

/** The SecretStore reference of a test's database login ([withMountedLogin]). */
const val TEST_LOGIN_REF = "db-test-login"

/**
 * A test login over a Testcontainers role, given the way a service gets one: [password] is mounted in SecretStore under
 * [mountRoot] and read by [SecretStoreCredentialsProvider] (STK-29, coordinator ruling INV-Q-4570); the HA event-log
 * pool copies the provider. A pool password set in code fails the second pass of the pool check (INV-Q-4603).
 */
fun ApplicationContextRunner.withMountedLogin(
    mountRoot: Path,
    password: String,
): ApplicationContextRunner {
    Files.writeString(mountRoot.resolve(TEST_LOGIN_REF), "$password\n")
    return withPropertyValues(
        "gst.bindings.secret-store.mount-root=$mountRoot",
        "service-runtime.datasource.credential=$TEST_LOGIN_REF",
    )
}

/**
 * Sets the application pool (bean `dataSource`) in a test after its first check (an unordered post-processor runs after
 * [PoolConfigurationGuard]): a test that bypasses the first pass sets a refused setting here, and must undo it before
 * the second pass ([PoolConfigurationRecheck], INV-Q-4603). Test code only.
 */
fun ApplicationContextRunner.withApplicationPool(setting: (HikariDataSource) -> Unit): ApplicationContextRunner =
    withPool("dataSource", setting)

/** As [withApplicationPool], for the pool of bean [poolBean] (the HA event-log pool copies no password, INV-Q-4570). */
fun ApplicationContextRunner.withPool(
    poolBean: String,
    setting: (HikariDataSource) -> Unit,
): ApplicationContextRunner =
    withBean("testPoolSetting-$poolBean", BeanPostProcessor::class.java, {
        object : BeanPostProcessor {
            override fun postProcessAfterInitialization(
                bean: Any,
                beanName: String,
            ): Any {
                if (beanName == poolBean) setting((bean as DataSource).unwrap(HikariDataSource::class.java))
                return bean
            }
        }
    })
