package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.application.counterparty.ApplyStructureNode
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.InboxConsumer
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.RebuildRequiredIncident
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.productruntime.ProductEventTypes
import com.dynokore.platform.tenancykernel.CurrentConnection
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.withSettings
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.util.logging.Handler
import java.util.logging.LogRecord

/**
 * INV-Q-16993 A (INV-AC-16991): `StructureFeed` is an auto-configuration ordered after L1, so its `@ConditionalOnBean`
 * guards see the event-log beans; a logging default sink backs off to a sink of the service's own.
 */
class StructureFeedWiringTest {
    private val l1 = "com.dynokore.platform.serviceruntime.composition.L1CompositionAutoConfiguration"

    // plain (lite) bean holders: not @Configuration, so the application's component scan never picks them up
    class UserConfiguration {
        @Bean fun apply(): ApplyStructureNode = mock(ApplyStructureNode::class.java)

        @Bean fun mapper(): ObjectMapper = ObjectMapper()

        @Bean fun catalogue(): EventCatalogue = mock(EventCatalogue::class.java)

        @Bean fun clock(): Clock = Clock.systemUTC()
    }

    class EventLogBeans {
        @Bean fun reader(): EventLogReader = mock(EventLogReader::class.java)

        @Bean
        fun scope(): TenantScope {
            // TenancyJooq needs one runner that is also the CurrentConnection (as TransactionRunnerImpl is)
            val runner =
                mock(
                    TransactionRunner::class.java,
                    withSettings().extraInterfaces(CurrentConnection::class.java),
                )
            return TenantScope(mock(TenantContext::class.java), runner, runner as CurrentConnection, "masterdata-svc")
        }
    }

    class ProductSinkBeans {
        @Bean fun ownSink(): RebuildRequiredSink = RebuildRequiredSink { }
    }

    private val runner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(StructureFeed::class.java))
            .withUserConfiguration(UserConfiguration::class.java)

    private fun subscription(consumer: Any) =
        JdbcInboxConsumer::class.java.getDeclaredField("subscription").let {
            it.isAccessible = true
            it.get(consumer) as ConsumerSubscription
        }

    @Test
    fun `INV-AC-16991 with the event-log beans the two inbox consumers exist`() {
        runner.withUserConfiguration(EventLogBeans::class.java).run { ctx ->
            ctx.startupFailure?.let { throw it }
            val consumers = ctx.getBeansOfType(JdbcInboxConsumer::class.java)
            assertThat(consumers.keys).containsExactlyInAnyOrder("structureConsumer", "entityProfileConsumer")
            assertThat(subscription(consumers.getValue("structureConsumer")))
                .extracting("consumer", "topic", "schema")
                .containsExactly("masterdata-svc", ProductEventTypes.ORGANISATION_STRUCTURE_CHANGED_TOPIC, "masterdata")
            assertThat(subscription(consumers.getValue("entityProfileConsumer")))
                .extracting("consumer", "topic", "schema")
                .containsExactly("masterdata-svc", ProductEventTypes.ENTITY_PROFILE_CHANGED_TOPIC, "masterdata")
            assertThat(ctx.getBean(InboxConsumer::class.java)).isSameAs(consumers["structureConsumer"])
        }
    }

    @Test
    fun `INV-AC-16991 without the event-log beans the context starts with no consumer and the handler`() {
        runner.run { ctx ->
            ctx.startupFailure?.let { throw it }
            assertThat(ctx.getBeansOfType(JdbcInboxConsumer::class.java)).isEmpty()
            assertThat(ctx.containsBean("structureNodeFeedHandler")).isTrue()
        }
    }

    @Test
    fun `INV-AC-16991 default sink is the logging sink when the context supplies none`() {
        runner.withUserConfiguration(EventLogBeans::class.java).run { ctx ->
            val sinks = ctx.getBeansOfType(RebuildRequiredSink::class.java)
            assertThat(sinks).hasSize(1)
            assertThat(sinks.values.single().javaClass).isEqualTo(LoggingRebuildRequiredSink::class.java)
        }
    }

    @Test
    fun `INV-AC-16991 a sink of the product is the only sink`() {
        runner.withUserConfiguration(EventLogBeans::class.java, ProductSinkBeans::class.java).run { ctx ->
            val sinks = ctx.getBeansOfType(RebuildRequiredSink::class.java)
            assertThat(sinks.keys).containsExactly("ownSink")
            assertThat(ctx.getBeansOfType(JdbcInboxConsumer::class.java)).hasSize(2)
        }
    }

    @Test
    fun `INV-AC-16991 the logging sink writes one ERROR line with consumer topic offset and watermark`() {
        val records = mutableListOf<LogRecord>()
        val logger =
            java.util.logging.Logger
                .getLogger(LoggingRebuildRequiredSink::class.java.name)
        val handler =
            object : Handler() {
                override fun publish(record: LogRecord) {
                    records += record
                }

                override fun flush() = Unit

                override fun close() = Unit
            }
        logger.addHandler(handler)
        try {
            LoggingRebuildRequiredSink().raise(RebuildRequiredIncident("c-1", "t-1", 7L, 9L, Instant.EPOCH))
        } finally {
            logger.removeHandler(handler)
        }
        assertThat(records).hasSize(1)
        assertThat(records.single().level).isEqualTo(java.util.logging.Level.SEVERE)
        val line = java.text.MessageFormat.format(records.single().message, *records.single().parameters)
        assertThat(line).contains("c-1", "t-1", "7", "9")
    }

    @Test
    fun `INV-AC-16991 registration is an auto-configuration after L1 listed once in the imports file`() {
        val annotation = StructureFeed::class.java.getAnnotation(AutoConfiguration::class.java)
        assertThat(annotation.afterName.toList()).containsExactly(l1)
        assertThat(StructureFeed::class.java.getAnnotation(Configuration::class.java)).isNull()
        val imports =
            StructureFeed::class.java.classLoader
                .getResources("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")
                .toList()
                .filter { it.path.contains("masterdata-bootstrap") }
                .flatMap { Files.readAllLines(Path.of(it.toURI())) }
                .filter { it.isNotBlank() && !it.startsWith("#") }
        // INV-T-18775 adds RatePackFeed beside it; StructureFeed is still listed exactly once
        assertThat(imports)
            .containsExactly(
                "com.dynokore.gst.masterdata.bootstrap.StructureFeed",
                "com.dynokore.gst.masterdata.bootstrap.RatePackFeed",
            )
    }
}
