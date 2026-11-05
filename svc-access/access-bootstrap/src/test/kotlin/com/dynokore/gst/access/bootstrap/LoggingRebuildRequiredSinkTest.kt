package com.dynokore.gst.access.bootstrap

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.dynokore.platform.outboxandqueue.RebuildRequiredIncident
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Instant

class LoggingRebuildRequiredSinkTest {
    @Test
    fun `raise writes one ERROR line holding the consumer, topic, next offset and watermark`() {
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        val logger = LoggerFactory.getLogger(LoggingRebuildRequiredSink::class.java) as Logger
        logger.addAppender(appender)
        try {
            LoggingRebuildRequiredSink().raise(
                RebuildRequiredIncident("break-glass-report", "access.events", 42L, 57L, Instant.EPOCH),
            )
            val event = appender.list.single()
            assertThat(event.level).isEqualTo(Level.ERROR)
            assertThat(event.formattedMessage)
                .contains("break-glass-report")
                .contains("access.events")
                .contains("42")
                .contains("57")
        } finally {
            logger.detachAppender(appender)
        }
    }
}
