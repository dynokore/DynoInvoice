package com.dynokore.platform.serviceruntime.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC

/**
 * The masked-field test (this task's done-when): proves invoice content set
 * through Mapped Diagnostic Context — by a bug, or a third-party library's own
 * instrumentation, not through [MdcContext] — never reaches an emitted log
 * event, while the allow-listed keys (STK-35, CS-34) pass through untouched.
 */
class MdcAllowListTurboFilterTest {
    private val context = LoggerFactory.getILoggerFactory() as LoggerContext
    private val appender = ListAppender<ILoggingEvent>()
    private val logger = context.getLogger(MdcAllowListTurboFilterTest::class.java)

    @BeforeEach
    fun setUp() {
        context.addTurboFilter(MdcAllowListTurboFilter())
        appender.context = context
        appender.start()
        logger.addAppender(appender)
        logger.level = Level.INFO
    }

    @AfterEach
    fun tearDown() {
        context.turboFilterList.removeIf { it is MdcAllowListTurboFilter }
        logger.detachAppender(appender)
        MDC.clear()
    }

    @Test
    fun `invoice content set through a raw MDC put never reaches the emitted log event`() {
        MdcContext.put(MdcKey.CORRELATION_ID, "corr-123")
        MDC.put("customer_name", "Alice Tan") // a bug: raw MDC.put, not MdcContext — carries invoice content (CS-34)

        logger.info("document processed")

        val event = appender.list.single()
        event.mdcPropertyMap["correlation_id"] shouldBe "corr-123"
        event.mdcPropertyMap.containsKey("customer_name") shouldBe false
    }

    @Test
    fun `every allow-listed key survives untouched`() {
        MdcKey.entries.forEach { MdcContext.put(it, "value-${it.key}") }

        logger.info("boundary state transition")

        val event = appender.list.single()
        MdcKey.entries.forEach { key -> event.mdcPropertyMap[key.key] shouldBe "value-${key.key}" }
    }
}
