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
import com.dynokore.platform.serviceruntime.composition.L1CompositionAutoConfiguration
import com.dynokore.platform.serviceruntime.composition.ServiceDataSources
import com.dynokore.platform.serviceruntime.http.IdempotencyStore
import com.dynokore.platform.serviceruntime.http.JobCommandServlet
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.jobs.DeadlineSource
import com.dynokore.platform.serviceruntime.jobs.JobCommandAuthorizer
import com.dynokore.platform.serviceruntime.jobs.JobCommands
import com.dynokore.platform.serviceruntime.operation.OperationRunner
import com.dynokore.platform.serviceruntime.pool.LanedDataSource
import com.dynokore.platform.tenancykernel.CurrentConnection
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
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
import org.springframework.web.client.RestClient
import java.time.LocalDate
import javax.sql.DataSource

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
    class AutoConfigurationOnlyApplication

    private val service =
        ApplicationContextRunner()
            .withUserConfiguration(AutoConfigurationOnlyApplication::class.java)
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
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
            context.getBean(DataSource::class.java)
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

    @Test
    fun `one application pool per instance and no role DataSource, the event log collapses into it on one instance`() {
        for (eventLogUrl in listOf(null, "jdbc:postgresql://localhost:5432/submission")) {
            val properties = listOfNotNull(eventLogUrl?.let { "gst.db.event-log-url=$it" }).toTypedArray()
            tpl16.withPropertyValues(*properties).run { context ->
                context.startupFailure shouldBe null
                // one pool: a second application pool bean, or any role DataSource, fails here (INV-Q-3955)
                context.getBeanNamesForType(DataSource::class.java).toList() shouldBe listOf("dataSource")
                // the pool under its lanes (INV-REQ-1230 b1): one HikariCP pool, no second one
                context.getBean(DataSource::class.java).shouldBeInstanceOf<LanedDataSource>()
                context.getBean(TenantScope::class.java).workloadPrincipal shouldBe "svc:submission-svc"
            }
        }
    }

    @Test
    fun `a separate event-log instance gets its own pool of at most 3, minimumIdle 1, app pool stays sole candidate`() {
        val ha = tpl16.withPropertyValues("gst.db.event-log-url=jdbc:postgresql://event-log:5432/eventlog")
        ha.run { context ->
            context.startupFailure shouldBe null
            context.getBeanNamesForType(DataSource::class.java).toSet() shouldBe
                setOf("dataSource", ServiceDataSources.EVENT_LOG)
        }
        ha.withPropertyValues("service-runtime.event-log-pool.minimum-idle=2").run { context ->
            context.startupFailure shouldBe null
        }
        ha.withPropertyValues("service-runtime.event-log-pool.minimum-idle=4").run { context ->
            generateSequence(context.startupFailure.shouldNotBeNull()) { it.cause }.any {
                it.message.orEmpty().contains("minimum-idle must be 0..3")
            } shouldBe true
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
