package com.dynokore.gst.aggregation.bootstrap

import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.CurrentConnection
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import java.nio.file.Path
import javax.sql.DataSource

/**
 * TPL-16 and TPL-18 (P109): the service built the way TPL-18 writes it. Every port the adapters inject is a bean over
 * the one DataSource from `spring.datasource.*`; there is no role DataSource.
 */
class Tpl16ServiceCompositionTest {
    @Test
    fun `every port the service injects is a bean over the one application DataSource`(
        @TempDir dir: Path,
    ) {
        AggregationContext.offline(dir).run { context ->
            context.startupFailure.shouldBeNull()
            listOf(
                TransactionRunner::class,
                CurrentConnection::class,
                TenantScope::class,
                OutboxWriter::class,
            ).forEach { context.getBean(it.java) }
            context.getBeanNamesForType(DataSource::class.java).toList() shouldBe listOf("dataSource")
            context.getBean(TenantScope::class.java).workloadPrincipal shouldBe "svc:aggregation-svc"
            catalogueTypes(context) shouldBe AggregationEvents.entries.map { it.eventType }.toSet()
        }
    }

    @Test
    fun `the management port and the health groups are configured`(
        @TempDir dir: Path,
    ) {
        AggregationContext.offline(dir).run { context ->
            val environment = context.environment
            environment.getProperty("management.server.port") shouldBe "8081"
            environment.getProperty("management.endpoint.health.group.live.include") shouldBe "livenessState"
            environment.getProperty("management.endpoints.web.exposure.include") shouldBe
                "health,prometheus,diagnostics"
        }
    }

    private fun catalogueTypes(context: ApplicationContext): Set<String> =
        context
            .getBean(EventCatalogue::class.java)
            .entries()
            .map { it.eventType }
            .toSet()
}
