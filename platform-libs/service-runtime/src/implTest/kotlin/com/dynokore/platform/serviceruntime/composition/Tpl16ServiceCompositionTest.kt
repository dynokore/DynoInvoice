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
import com.dynokore.platform.tenancykernel.CurrentConnection
import com.dynokore.platform.tenancykernel.TransactionRunner
import com.zaxxer.hikari.HikariDataSource
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
 * which component scan registers before the application class's `@Import`, declares the named role DataSources and
 * its other inputs. Every L1 port that service injects is a bean, whatever the order of the two roots.
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
                "service-runtime.modules.schema=submission",
                "service-runtime.idempotency.schema=submission",
            )

    @ParameterizedTest
    @EnumSource(Composition::class)
    fun `every port the service injects is a bean, over the DataSource from spring-datasource`(composition: Composition) {
        service.withUserConfiguration(SubmissionBootstrap::class.java, composition.application).run { context ->
            context.startupFailure shouldBe null
            val application = context.getBean(DataSource::class.java).shouldBeInstanceOf<HikariDataSource>()
            application.jdbcUrl shouldBe "jdbc:postgresql://localhost:5432/submission"
            listOf(
                TransactionRunner::class, CurrentConnection::class, TenantScope::class, OutboxWriter::class, AuditWriter::class,
                OperationRunner::class, PolicyReader::class, JobCommands::class, IdempotencyStore::class,
            ).forEach { context.getBean(it.java) }
            // the job command HTTP wiring
            context.getBean("jobCommandServlet", ServletRegistrationBean::class.java).servlet.shouldBeInstanceOf<JobCommandServlet>()
        }
    }

    @Test
    fun `without a DataSource there is no TransactionRunner`() {
        service.withUserConfiguration(SubmissionBootstrap::class.java, Tpl18WithoutDataSourceApplication::class.java).run { context ->
            context.startupFailure shouldBe null
            context.getBeanNamesForType(DataSource::class.java).toList() shouldBe listOf(ServiceDataSources.WORKER)
            listOf(TransactionRunner::class, CurrentConnection::class, TenantScope::class, OperationRunner::class, PolicyReader::class)
                .forEach { context.getBeanNamesForType(it.java).size shouldBe 0 }
            context.containsBean("jobCommandServlet") shouldBe false
        }
    }

    /** The service's own `bootstrap` configuration (TPL-18): its role DataSources and inputs, not the application one. */
    @Configuration(proxyBeanMethods = false)
    class SubmissionBootstrap {
        // not a default candidate, so Spring Boot still creates the application DataSource and injects it by type
        @Bean(ServiceDataSources.WORKER, defaultCandidate = false)
        fun workerDataSource(): DataSource = mockk(relaxed = true)

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
