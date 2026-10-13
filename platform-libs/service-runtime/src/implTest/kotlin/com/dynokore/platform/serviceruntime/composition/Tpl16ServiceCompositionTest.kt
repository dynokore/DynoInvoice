package com.dynokore.platform.serviceruntime.composition

import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.outboxandqueue.TenantScope
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
import com.zaxxer.hikari.HikariDataSource
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.web.servlet.ServletRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import javax.sql.DataSource

/**
 * INV-T-3881 (ruling INV-Q-3889, INV-Q-3710): a TPL-16 service composed as TPL-18. Its application DataSource comes
 * only from `spring.datasource.*`, through Spring Boot's DataSourceAutoConfiguration (TPL-16, TPL-14). Its bootstrap,
 * which component scan registers before the application class's `@Import`, declares its other inputs; there are no
 * role DataSources (owner ruling INV-Q-3955). Every L1 port that service injects is a bean, whatever the order of the two roots.
 */
class Tpl16ServiceCompositionTest {
    private val service =
        ApplicationContextRunner()
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
            .withPropertyValues(
                // TPL-16 application.yaml: the application role, lazily pooled (no connection at start)
                "spring.datasource.url=jdbc:postgresql://localhost:5432/submission",
                "spring.datasource.username=submission_app",
                "service-runtime.workload-identity.audience=submission-svc",
                "gst.service=submission-svc",
                "gst.instance-id=submission-svc-0",
                "service-runtime.pool.profile=minimal",
                "service-runtime.modules.schema=submission",
                "service-runtime.idempotency.schema=submission",
            )

    @ParameterizedTest
    @EnumSource(Composition::class)
    fun `every port the service injects is a bean, over the DataSource from spring-datasource`(composition: Composition) {
        service.withUserConfiguration(SubmissionBootstrap::class.java, composition.application).run { context ->
            context.startupFailure shouldBe null
            val application = context.getBean(DataSource::class.java).unwrap(HikariDataSource::class.java)
            application.jdbcUrl shouldBe "jdbc:postgresql://localhost:5432/submission"
            listOf(
                TransactionRunner::class, CurrentConnection::class, TenantScope::class, OutboxWriter::class, AuditWriter::class,
                OperationRunner::class, PolicyReader::class, JobCommands::class, IdempotencyStore::class,
            ).forEach { context.getBean(it.java) }
            // the job command HTTP wiring
            context.getBean("jobCommandServlet", ServletRegistrationBean::class.java).servlet.shouldBeInstanceOf<JobCommandServlet>()
        }
    }

    @ParameterizedTest
    @EnumSource(Composition::class)
    fun `one application pool per instance and no role DataSource, the event log collapses into it on the same instance`(
        composition: Composition,
    ) {
        for (eventLogUrl in listOf(null, "jdbc:postgresql://localhost:5432/submission")) {
            val properties = listOfNotNull(eventLogUrl?.let { "gst.db.event-log-url=$it" }).toTypedArray()
            service.withUserConfiguration(SubmissionBootstrap::class.java, composition.application).withPropertyValues(*properties).run { context ->
                context.startupFailure shouldBe null
                // one pool: a second application pool bean, or any role DataSource, fails here (INV-Q-3955)
                context.getBeanNamesForType(DataSource::class.java).toList() shouldBe listOf("dataSource")
                // the pool under its lanes (INV-REQ-1230 b1): one HikariCP pool, no second one
                context.getBean(DataSource::class.java).shouldBeInstanceOf<LanedDataSource>().unwrap(HikariDataSource::class.java).maximumPoolSize shouldBe 7
                context.getBean(TenantScope::class.java).workloadPrincipal shouldBe "svc:submission-svc"
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Composition::class)
    fun `a separate event-log instance gets its own pool of at most 3 with minimumIdle 1, the application one stays the only candidate`(
        composition: Composition,
    ) {
        val ha = service.withUserConfiguration(SubmissionBootstrap::class.java, composition.application)
            .withPropertyValues("gst.db.event-log-url=jdbc:postgresql://event-log:5432/eventlog")
        ha.run { context ->
            context.startupFailure shouldBe null
            context.getBeanNamesForType(DataSource::class.java).toSet() shouldBe setOf("dataSource", ServiceDataSources.EVENT_LOG)
            val eventLog = context.getBean(ServiceDataSources.EVENT_LOG, HikariDataSource::class.java)
            eventLog.jdbcUrl shouldBe "jdbc:postgresql://event-log:5432/eventlog"
            eventLog.username shouldBe "submission_app"
            eventLog.maximumPoolSize shouldBe 3
            eventLog.minimumIdle shouldBe 1
            // the application DataSource is still the one an unqualified injection gets
            context.getBean(DataSource::class.java).unwrap(HikariDataSource::class.java).jdbcUrl shouldBe "jdbc:postgresql://localhost:5432/submission"
        }
        ha.withPropertyValues("service-runtime.event-log-pool.minimum-idle=2").run { context ->
            context.getBean(ServiceDataSources.EVENT_LOG, HikariDataSource::class.java).minimumIdle shouldBe 2
        }
        ha.withPropertyValues("service-runtime.event-log-pool.minimum-idle=4").run { context ->
            generateSequence(context.startupFailure.shouldNotBeNull()) { it.cause }.any {
                it.message.orEmpty().contains("minimum-idle must be 0..3")
            } shouldBe true
        }
    }

    @Test
    fun `gst-service is format-checked at start-up for the workload principal`() {
        service.withUserConfiguration(SubmissionBootstrap::class.java, Composition.entries.first().application)
            .withPropertyValues("gst.service=Submission_Svc")
            .run { context ->
                generateSequence(context.startupFailure.shouldNotBeNull()) { it.cause }.any {
                    it.message.orEmpty().contains("gst.service must be a lower-case service name")
                } shouldBe true
            }
    }

    @Test
    fun `without a DataSource there is no TransactionRunner`() {
        service.withUserConfiguration(SubmissionBootstrap::class.java, Tpl18WithoutDataSourceApplication::class.java).run { context ->
            context.startupFailure shouldBe null
            context.getBeanNamesForType(DataSource::class.java).toList() shouldBe emptyList()
            listOf(TransactionRunner::class, CurrentConnection::class, TenantScope::class, OperationRunner::class, PolicyReader::class)
                .forEach { context.getBeanNamesForType(it.java).size shouldBe 0 }
            context.containsBean("jobCommandServlet") shouldBe false
        }
    }

    /** The service's own `bootstrap` configuration (TPL-18): its inputs, no DataSource (INV-REQ-1230 b1). */
    @Configuration(proxyBeanMethods = false)
    class SubmissionBootstrap {
        @Bean
        fun eventCatalogue(): EventCatalogue = EventCatalogue(listOf(CatalogueEntry("SubmissionFailed", "submission", setOf("submission-svc"), contentTopic = false)))

        @Bean
        fun jobCommandAuthorizer(): JobCommandAuthorizer = mockk(relaxed = true)

        @Bean
        fun deadlineSource(): DeadlineSource = mockk(relaxed = true)

        @Bean
        fun endUserPrincipalResolver(): EndUserPrincipalResolver = EndUserPrincipalResolver { null }
    }
}
