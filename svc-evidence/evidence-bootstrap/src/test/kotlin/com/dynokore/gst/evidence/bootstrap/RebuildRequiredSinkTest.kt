package com.dynokore.gst.evidence.bootstrap

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.ConsumerOffset.Companion.CONSUMER_OFFSET
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.DocumentLocator.Companion.DOCUMENT_LOCATOR
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.LogRecord
import com.dynokore.platform.outboxandqueue.RebuildRequired
import com.dynokore.platform.outboxandqueue.RebuildRequiredIncident
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** INV-Q-10834 A: an incident of a consumer that needs a rebuild is logged at ERROR and nothing else happens. */
class RebuildRequiredSinkTest {
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

    @Test
    fun `with no sink supplied the context binds the logging sink`(
        @TempDir dir: Path,
    ) {
        EvidenceContext.offline(dir).run { context ->
            context.startupFailure shouldBe null
            context.getBean(RebuildRequiredSink::class.java).shouldBeInstanceOf<LoggingRebuildRequiredSink>()
        }
    }

    @Test
    fun `a sink supplied by the context replaces the logging one`(
        @TempDir dir: Path,
    ) {
        val supplied = RebuildRequiredSink { }
        EvidenceContext.offline(dir).withBean(RebuildRequiredSink::class.java, { supplied }).run { context ->
            context.startupFailure shouldBe null
            context.getBean(RebuildRequiredSink::class.java) shouldBeSameInstanceAs supplied
            context.getBeanNamesForType(LoggingRebuildRequiredSink::class.java).size shouldBe 0
        }
    }

    @Test
    fun `the sink logs the consumer, the topic and the offsets at ERROR and does nothing else`() {
        val incident = RebuildRequiredIncident("evidence-svc", "gst.x.y.v1", 5, 9, Instant.now())
        capturing { logged ->
            LoggingRebuildRequiredSink().raise(incident)
            logged.size shouldBe 1
            logged.single().level shouldBe Level.ERROR
            logged.single().formattedMessage.apply {
                shouldContain("evidence-svc")
                shouldContain("gst.x.y.v1")
                shouldContain("next offset 5")
                shouldContain("purge watermark 9")
            }
        }
    }

    @Test
    fun `the locator consumer of a context with no sink raises one incident, stores nothing and keeps its offset`(
        @TempDir dir: Path,
    ) {
        EvidenceContext.online(dir).run { context ->
            context.startupFailure shouldBe null
            val dsl = context.getBean(DSLContext::class.java)
            val scope = context.getBean(TenantScope::class.java)
            val behind =
                object : EventLogReader {
                    override fun read(
                        topic: String,
                        afterOffset: Long,
                        limit: Int,
                    ): List<LogRecord> = throw RebuildRequired(topic, afterOffset + 1, afterOffset + 10)
                }
            val consumer =
                LocatorConsumerConfiguration().locatorConsumer(
                    context.getBean(EventCatalogue::class.java),
                    behind,
                    scope,
                    context.getBean(RebuildRequiredSink::class.java),
                    Clock.systemUTC(),
                    dsl,
                )
            val runner = context.getBean(TransactionRunner::class.java)
            val tenant = TenantId(UUID.randomUUID())

            fun <T> tx(body: () -> T): T = scope.bind(tenant, "svc:evidence-svc") { runner.inTransaction { body() } }

            fun offset() =
                tx {
                    dsl
                        .select(CONSUMER_OFFSET.NEXT_OFFSET)
                        .from(CONSUMER_OFFSET)
                        .where(CONSUMER_OFFSET.CONSUMER.eq(LocatorConsumerConfiguration.CONSUMER))
                        .and(CONSUMER_OFFSET.TOPIC.eq(LocatorConsumerConfiguration.TOPIC))
                        .fetchOne(0)
                }
            val before = offset()
            capturing { logged ->
                consumer.pollOnce().rebuildRequired shouldBe true
                consumer.pollOnce().rebuildRequired shouldBe true
                val errors = logged.filter { it.level == Level.ERROR }
                errors.size shouldBe 1
                errors.single().formattedMessage.apply {
                    shouldContain("evidence-svc")
                    shouldContain(LocatorConsumerConfiguration.TOPIC)
                    shouldContain("next offset 1")
                    shouldContain("purge watermark 10")
                }
            }
            offset() shouldBe before
            tx { dsl.fetchCount(DOCUMENT_LOCATOR) } shouldBe 0
        }
    }
}
