package com.dynokore.gst.tenancy.bootstrap

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.dynokore.gst.tenancy.adapters.election.AccessPointRouteChangedHandler
import com.dynokore.gst.tenancy.adapters.election.ConsumedElectionEvents
import com.dynokore.gst.tenancy.adapters.election.RulePackActivatedHandler
import com.dynokore.gst.tenancy.adapters.election.RulePackStagedHandler
import com.dynokore.gst.tenancy.application.election.AccessPointRouteStore
import com.dynokore.gst.tenancy.application.election.ScopePackStore
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.LogRecord
import com.dynokore.platform.outboxandqueue.RebuildRequired
import com.dynokore.platform.outboxandqueue.RebuildRequiredIncident
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.test.util.ReflectionTestUtils
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant

/** Ruling INV-Q-10031: the rebuild-required incident is logged at ERROR and nothing else happens. */
class RebuildRequiredSinkTest {
    private fun run(block: (ProfileEnv) -> Unit) {
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            block(ProfileEnv(context))
        }
    }

    private fun <T> capturing(block: (List<ILoggingEvent>) -> T): T {
        val logger = LoggerFactory.getLogger(LoggingRebuildRequiredSink::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        try {
            return block(appender.list)
        } finally {
            logger.detachAppender(appender)
        }
    }

    private val incident =
        RebuildRequiredIncident("tenancy-svc-rule-pack-staged", "gst.rules.rule-pack-staged.v1", 5, 9, Instant.now())

    @Test
    fun `the bound sink logs the topic and the reason at ERROR and does nothing else`() {
        run { env ->
            val sink = env.context.getBean(RebuildRequiredSink::class.java)
            assertThat(sink).isInstanceOf(LoggingRebuildRequiredSink::class.java)
            capturing { logged ->
                sink.raise(incident)
                assertThat(logged).hasSize(1)
                assertThat(logged.single().level).isEqualTo(Level.ERROR)
                assertThat(logged.single().formattedMessage)
                    .contains("gst.rules.rule-pack-staged.v1")
                    .contains("next offset 5")
                    .contains("purge watermark 9")
            }
        }
    }

    @Test
    fun `the three inbox consumers use the binding`() {
        run { env ->
            val sink = env.context.getBean(RebuildRequiredSink::class.java)
            val names = listOf("accessPointRouteChangedConsumer", "rulePackStagedConsumer", "rulePackActivatedConsumer")
            names.forEach { name ->
                val consumer = env.context.getBean(name)
                val deps = ReflectionTestUtils.getField(consumer, "deps")
                val provider = ReflectionTestUtils.getField(deps!!, "rebuildRequired") as ObjectProvider<*>
                assertThat(provider.getObject()).describedAs(name).isSameAs(sink)
            }
        }
    }

    @Test
    fun `a consumer stopped for a rebuild stays held and raises the incident once`() {
        run { env ->
            val ctx = env.context
            val behind =
                object : EventLogReader {
                    override fun read(
                        topic: String,
                        afterOffset: Long,
                        limit: Int,
                    ): List<LogRecord> = throw RebuildRequired(topic, afterOffset + 1, afterOffset + 10)
                }
            val factory = DefaultListableBeanFactory().also { it.registerSingleton("reader", behind) }
            val deps =
                ConsumerDependencies(
                    ctx.getBeanProvider(EventCatalogue::class.java),
                    factory.getBeanProvider(EventLogReader::class.java),
                    ctx.getBeanProvider(TenantScope::class.java),
                    ctx.getBeanProvider(RebuildRequiredSink::class.java),
                    ctx.getBeanProvider(TenantContext::class.java),
                    ctx.getBeanProvider(TransactionRunner::class.java),
                    ctx.getBeanProvider(Clock::class.java),
                )
            val mapper = JsonMapper.builder().build()
            val handlers: Map<String, EventHandler> =
                mapOf(
                    ConsumedElectionEvents.ACCESS_POINT_ROUTE_CHANGED_TOPIC to
                        AccessPointRouteChangedHandler(ctx.getBean(AccessPointRouteStore::class.java), mapper),
                    ConsumedElectionEvents.RULE_PACK_STAGED_TOPIC to
                        RulePackStagedHandler(ctx.getBean(ScopePackStore::class.java), mapper),
                    ConsumedElectionEvents.RULE_PACK_ACTIVATED_TOPIC to
                        RulePackActivatedHandler(ctx.getBean(ScopePackStore::class.java), mapper),
                )
            handlers.forEach { (topic, handler) ->
                capturing { logged ->
                    val consumer = deps.consumer("probe-$topic", topic, handler)
                    assertThat(consumer.pollOnce().rebuildRequired).describedAs(topic).isTrue()
                    assertThat(consumer.pollOnce().rebuildRequired).describedAs(topic).isTrue()
                    assertThat(consumer.pollOnce().applied).isZero()
                    assertThat(logged.filter { it.level == Level.ERROR && it.formattedMessage.contains(topic) })
                        .describedAs("one incident per stop on $topic")
                        .hasSize(1)
                }
            }
        }
    }
}
