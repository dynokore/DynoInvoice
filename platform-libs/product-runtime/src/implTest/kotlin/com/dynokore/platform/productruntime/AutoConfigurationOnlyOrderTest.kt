package com.dynokore.platform.productruntime

import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.configresolver.DelegationProbe
import com.dynokore.platform.configresolver.PolicyHistory
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.UnknownVariationPoint
import com.dynokore.platform.configresolver.VariationPointCatalogue
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.productruntime.catalogue.GstVariationPointCatalogue
import com.dynokore.platform.serviceruntime.binding.InstallationBindingKey
import com.dynokore.platform.serviceruntime.composition.L1CompositionAutoConfiguration
import com.dynokore.platform.serviceruntime.http.IdempotencyStore
import com.dynokore.platform.serviceruntime.http.JobCommandServlet
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.jobs.DeadlineSource
import com.dynokore.platform.serviceruntime.jobs.JobCommandAuthorizer
import com.dynokore.platform.serviceruntime.jobs.JobCommands
import com.dynokore.platform.serviceruntime.operation.OperationRunner
import com.dynokore.platform.tenancykernel.CurrentConnection
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.web.servlet.ServletRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.web.client.RestClient
import java.time.LocalDate

/**
 * INV-T-3881's third composition, auto-configuration only (rulings INV-Q-3877, INV-Q-3889), with the REAL
 * ProductRuntimeConfiguration (INV-T-4747, coordinator ruling INV-Q-4734: service-runtime's tests may not name
 * product-runtime's package, so this case runs here). The service imports neither root: `@EnableAutoConfiguration`
 * registers both from `AutoConfiguration.imports`. L1CompositionAutoConfiguration names ProductRuntimeConfiguration in
 * `afterName`, so config-resolver's ports exist only when that string still matches the real class. The other two
 * compositions (TPL-18's order and the reverse) stay in service-runtime's implTest over a stand-in
 * (ConfigResolverImportOrderTest, Tpl16ServiceCompositionTest) and in WiringTest here.
 */
class AutoConfigurationOnlyOrderTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(DefaultSchemaConfiguration::class)
    class AutoConfigurationOnlyApplication

    private val service =
        ApplicationContextRunner()
            .withUserConfiguration(AutoConfigurationOnlyApplication::class.java)
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
            .withBean(InstallationBindingKey::class.java, { FixedBindingKeySource.installationBindingKey() })
            .withBean("internalRestClientBuilder", RestClient.Builder::class.java, { RestClient.builder() })
            .withPropertyValues(
                "gst.service=submission-svc",
                "gst.instance-id=submission-svc-0",
                "service-runtime.workload-identity.audience=submission-svc",
                "service-runtime.modules.schema=submission",
                "spring.datasource.url=jdbc:postgresql://localhost:1/submission",
                "spring.datasource.username=submission_svc_runtime",
                "service-runtime.pool.profile=minimal",
            )

    @Test
    fun `config-resolver's ports are beans and the PolicyReader resolves through product-runtime's catalogue`() {
        service.run { context ->
            context.startupFailure?.let { throw it }
            context.getBean(CurrentConnection::class.java)
            // the ports that need only the classpath of product-runtime's implTest (the writers need audit-log)
            listOf(PolicyReader::class, PolicyHistory::class).forEach { context.getBean(it.java) }
            context.getBean(VariationPointCatalogue::class.java).shouldBeInstanceOf<GstVariationPointCatalogue>()
            // the optional DelegationProbe product-runtime provides
            context.getBean(DelegationProbe::class.java)
            shouldThrow<UnknownVariationPoint> {
                context
                    .getBean(PolicyReader::class.java)
                    .resolve(VariationPointKey("VP-999"), PolicyScope.installation(), LocalDate.of(2026, 10, 12))
            }
        }
    }

    // INV-T-4747 (coordinator ruling INV-Q-4769): the three assertion groups of Tpl16ServiceCompositionTest (f9eef18)
    // for the AUTO_CONFIGURATION_ONLY composition, moved with identical values over that test's property set.
    private val tpl16 =
        ApplicationContextRunner()
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
            .withBean(InstallationBindingKey::class.java, { FixedBindingKeySource.installationBindingKey() })
            .withPropertyValues(
                "spring.datasource.url=jdbc:postgresql://localhost:5432/submission",
                "spring.datasource.username=submission_app",
                "service-runtime.workload-identity.audience=submission-svc",
                "gst.service=submission-svc",
                "gst.instance-id=submission-svc-0",
                "service-runtime.pool.profile=minimal",
                "service-runtime.modules.schema=submission",
                "service-runtime.idempotency.schema=submission",
            ).withUserConfiguration(SubmissionBootstrap::class.java, AutoConfigurationOnlyApplication::class.java)

    @Test
    fun `every port the service injects is a bean, over the DataSource from spring-datasource`() {
        tpl16.run { context ->
            context.startupFailure shouldBe null
            listOf(
                TransactionRunner::class,
                CurrentConnection::class,
                TenantScope::class,
                OutboxWriter::class,
                AuditWriter::class,
                OperationRunner::class,
                PolicyReader::class,
                JobCommands::class,
                IdempotencyStore::class,
            ).forEach { context.getBean(it.java) }
            // the job command HTTP wiring
            context
                .getBean(
                    "jobCommandServlet",
                    ServletRegistrationBean::class.java,
                ).servlet
                .shouldBeInstanceOf<JobCommandServlet>()
        }
    }

    /** The service's own `bootstrap` configuration (TPL-18): its inputs, no DataSource (INV-REQ-1230 b1). */
    @Configuration(proxyBeanMethods = false)
    class SubmissionBootstrap {
        @Bean
        fun eventCatalogue(): EventCatalogue =
            EventCatalogue(
                listOf(CatalogueEntry("SubmissionFailed", "submission", setOf("submission-svc"), contentTopic = false)),
            )

        @Bean
        fun jobCommandAuthorizer(): JobCommandAuthorizer = mockk(relaxed = true)

        @Bean
        fun deadlineSource(): DeadlineSource = mockk(relaxed = true)

        @Bean
        fun endUserPrincipalResolver(): EndUserPrincipalResolver = EndUserPrincipalResolver { null }
    }

    @Test
    fun `afterName still names the real class`() {
        // The ordering hint of L1CompositionAutoConfiguration is a string (INV-Q-3877 (a)): it must stay the real name.
        val hint =
            L1CompositionAutoConfiguration::class.java
                .getAnnotation(org.springframework.boot.autoconfigure.AutoConfiguration::class.java)
                .afterName
        (ProductRuntimeConfiguration::class.java.name in hint) shouldBe true
    }
}
