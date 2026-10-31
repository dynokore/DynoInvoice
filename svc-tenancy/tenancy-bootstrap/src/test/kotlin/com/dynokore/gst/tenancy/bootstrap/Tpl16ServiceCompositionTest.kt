package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.application.installation.InstallationFactRepository
import com.dynokore.gst.tenancy.application.structure.StructureChangePublisher
import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import javax.sql.DataSource

/**
 * TPL-16 / TPL-18 (P109): the service composed the way production composes it, through the real application class and
 * the real `ApplicationContextRunner` machinery, over the DataSource from `spring.datasource.*`. Every L1 port the
 * adapters inject is a bean, and no role DataSource exists (owner ruling INV-Q-4196).
 */
class Tpl16ServiceCompositionTest {
    private val service =
        WebApplicationContextRunner()
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
            // the packaged application.yaml (TPL-16) is part of what is tested
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withUserConfiguration(TenancyApplication::class.java)
            .withPropertyValues(
                "spring.datasource.url=jdbc:postgresql://localhost:5432/tenancy",
                "spring.datasource.username=tenancy_runtime",
                "service-runtime.workload-identity.audience=tenancy-svc",
                "gst.service=tenancy-svc",
                "gst.instance-id=tenancy-svc-0",
                "service-runtime.pool.profile=minimal",
                "service-runtime.modules.schema=tenancy",
                "management.server.port=8081",
                "gst.bindings.secret-store.mount-root=${TestSecrets.mount()}",
                "service-runtime.datasource.credential=db/tenancy_runtime",
            )

    @Test
    fun `every port the adapters inject is a bean over the one application pool`() {
        service.run { context ->
            context.startupFailure?.let { throw it }
            listOf(
                TransactionRunner::class,
                OutboxWriter::class,
                AuditWriter::class,
                EventCatalogue::class,
                InstallationFactRepository::class,
                StructureChangePublisher::class,
            ).forEach { assertThat(context.getBeansOfType(it.java)).describedAs(it.simpleName).isNotEmpty() }
            assertThat(context.getBeanNamesForType(DataSource::class.java).toList()).containsExactly("dataSource")
        }
    }
}
