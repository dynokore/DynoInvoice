package com.dynokore.platform.serviceruntime.composition

import com.dynokore.platform.configresolver.ConfigPurge
import com.dynokore.platform.configresolver.DelegationProbe
import com.dynokore.platform.configresolver.JdbcPolicyHistory
import com.dynokore.platform.configresolver.JdbcPolicyReader
import com.dynokore.platform.configresolver.JdbcPolicyWriter
import com.dynokore.platform.configresolver.PolicyHistory
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.PolicyWriter
import com.dynokore.platform.configresolver.ResolutionRecorder
import com.dynokore.platform.configresolver.UnknownVariationPoint
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.productruntime.ProductRuntimeConfiguration
import com.dynokore.platform.productruntime.RecordingCatalogue
import com.dynokore.platform.serviceruntime.ServiceRuntimeConfiguration
import com.dynokore.platform.tenancykernel.CurrentConnection
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.annotation.Configurations
import org.springframework.boot.context.annotation.ImportCandidates
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DeferredImportSelector
import org.springframework.context.annotation.Import
import org.springframework.core.type.AnnotationMetadata
import java.time.LocalDate
import javax.sql.DataSource

/**
 * INV-T-3881 (ruling INV-Q-3877, INV-Q-3710): config-resolver's ports are wired whatever the order in which a service
 * registers ServiceRuntimeConfiguration and ProductRuntimeConfiguration. Each composition is a service's application
 * class as TPL-18 writes it: its `@Import` of the two roots plus `@SpringBootApplication`'s auto-configuration, here
 * [PlatformAutoConfigurations], a deferred import of every platform-libs auto-configuration the classpath lists in
 * `AutoConfiguration.imports`, sorted as Spring Boot sorts them and processed, as Boot processes them, after all user
 * configuration. product-runtime is the stand-in [ProductRuntimeConfiguration].
 */
class ConfigResolverImportOrderTest {
    /** The inputs every service declares: a DataSource, its name, its schema (README port table). */
    private val service =
        ApplicationContextRunner()
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
            .withBean("dataSource", DataSource::class.java, { mockk<DataSource>(relaxed = true) }, { it.isPrimary = true })
            .withPropertyValues(
                "service-runtime.workload-identity.audience=submission-svc",
                "gst.service=submission-svc",
                "service-runtime.modules.schema=submission",
            )

    enum class Composition(
        val application: Class<*>,
    ) {
        /** TPL-18 as written: `@Import(ServiceRuntimeConfiguration::class, ProductRuntimeConfiguration::class)`. */
        TPL_18(Tpl18Application::class.java),
        REVERSED_IMPORT(ReversedApplication::class.java),
        AUTO_CONFIGURATION_ONLY(AutoConfiguredApplication::class.java),
    }

    @ParameterizedTest
    @EnumSource(Composition::class)
    fun `config-resolver's ports are beans and the PolicyReader resolves through product-runtime's catalogue`(composition: Composition) {
        service.withUserConfiguration(composition.application).run { context ->
            context.startupFailure shouldBe null
            listOf(PolicyReader::class, PolicyHistory::class, ConfigPurge::class, ResolutionRecorder::class, PolicyWriter::class)
                .forEach { context.getBean(it.java) }
            val catalogue = context.getBean(RecordingCatalogue::class.java)
            val reader = context.getBean(PolicyReader::class.java).shouldBeInstanceOf<JdbcPolicyReader>()
            val key = VariationPointKey("VP-20")
            shouldThrow<UnknownVariationPoint> { reader.resolve(key, PolicyScope.installation(), LocalDate.of(2026, 10, 12)) }
            catalogue.asked shouldContainExactly listOf(key)
            // the optional DelegationProbe product-runtime provides reaches the PolicyWriter
            val writer = context.getBean(PolicyWriter::class.java).shouldBeInstanceOf<JdbcPolicyWriter>()
            writer.delegation() shouldBeSameInstanceAs context.getBean(DelegationProbe::class.java)
        }
    }

    @ParameterizedTest
    @EnumSource(Composition::class)
    fun `without product-runtime's optional DelegationProbe the PolicyWriter is still wired, without one`(composition: Composition) {
        service.withUserConfiguration(composition.application)
            .withPropertyValues("${ProductRuntimeConfiguration.DELEGATION_PROBE}=false")
            .run { context ->
                context.startupFailure shouldBe null
                context.getBeanNamesForType(DelegationProbe::class.java).size shouldBe 0
                context.getBean(PolicyWriter::class.java).shouldBeInstanceOf<JdbcPolicyWriter>().delegation() shouldBe null
            }
    }

    @Test
    fun `without a VariationPointCatalogue no config-resolver port is a bean`() {
        listOf(ServiceOnlyApplication::class.java, ServiceOnlyAutoConfiguredApplication::class.java).forEach { application ->
            service.withUserConfiguration(application).run { context ->
                context.startupFailure shouldBe null
                context.getBean(CurrentConnection::class.java)
                listOf(PolicyReader::class, PolicyHistory::class, ConfigPurge::class, ResolutionRecorder::class, PolicyWriter::class)
                    .forEach { context.getBeanNamesForType(it.java).size shouldBe 0 }
            }
        }
    }

    @Test
    fun `a service's own PolicyReader still replaces the default under TPL-18`() {
        val own = mockk<PolicyReader>()
        service.withBean(PolicyReader::class.java, { own }).withUserConfiguration(Tpl18Application::class.java).run { context ->
            context.startupFailure shouldBe null
            context.getBean(PolicyReader::class.java) shouldBeSameInstanceAs own
            context.getBean(PolicyHistory::class.java).shouldBeInstanceOf<JdbcPolicyHistory>()
        }
    }

    /** TPL-18's application class, as every service writes it. */
    @Configuration(proxyBeanMethods = false)
    @Import(ServiceRuntimeConfiguration::class, ProductRuntimeConfiguration::class, PlatformAutoConfigurations::class)
    class Tpl18Application

    @Configuration(proxyBeanMethods = false)
    @Import(ProductRuntimeConfiguration::class, ServiceRuntimeConfiguration::class, PlatformAutoConfigurations::class)
    class ReversedApplication

    @Configuration(proxyBeanMethods = false)
    @Import(PlatformAutoConfigurations::class)
    class AutoConfiguredApplication

    /** A service without product-runtime: no catalogue. */
    @Configuration(proxyBeanMethods = false)
    @Import(ServiceRuntimeConfiguration::class, ServiceRuntimeAutoConfigurations::class)
    class ServiceOnlyApplication

    @Configuration(proxyBeanMethods = false)
    @Import(ServiceRuntimeAutoConfigurations::class)
    class ServiceOnlyAutoConfiguredApplication

    /** `@EnableAutoConfiguration` over the platform-libs classpath with product-runtime's stand-in on it. */
    class PlatformAutoConfigurations : DeferredImportSelector {
        override fun selectImports(importingClassMetadata: AnnotationMetadata): Array<String> = sorted(platform() + ProductRuntimeConfiguration::class.java)
    }

    /** The same without product-runtime. */
    class ServiceRuntimeAutoConfigurations : DeferredImportSelector {
        override fun selectImports(importingClassMetadata: AnnotationMetadata): Array<String> = sorted(platform())
    }

    private companion object {
        /** The platform-libs auto-configurations listed in `AutoConfiguration.imports` on the classpath. */
        fun platform(): List<Class<*>> = ImportCandidates.load(AutoConfiguration::class.java, ConfigResolverImportOrderTest::class.java.classLoader)
            .filter { it.startsWith("com.dynokore.platform.") }
            .map { Class.forName(it) }

        /** In Spring Boot's auto-configuration order (@AutoConfiguration before/after). */
        fun sorted(classes: List<Class<*>>): Array<String> = Configurations.getClasses(AutoConfigurations.of(*classes.toTypedArray())).map { it.name }.toTypedArray()

        fun JdbcPolicyWriter.delegation(): Any? = JdbcPolicyWriter::class.java.getDeclaredField("delegation").apply { isAccessible = true }.get(this)
    }
}
