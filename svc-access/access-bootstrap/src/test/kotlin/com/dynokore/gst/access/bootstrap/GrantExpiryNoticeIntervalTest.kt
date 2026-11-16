package com.dynokore.gst.access.bootstrap

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.time.Duration

/** The required interval of the grant expiry-notice scan, and its telemetry (INV-T-15799). */
class GrantExpiryNoticeIntervalTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(GrantExpiryNoticeProperties::class)
    class OnlyProperties

    private val runner = ApplicationContextRunner().withUserConfiguration(OnlyProperties::class.java)

    @Test
    fun `a context without the interval fails to start`() {
        runner.run { assertThat(it).hasFailed() }
    }

    @Test
    fun `a zero interval and a negative interval fail to start`() {
        listOf("PT0S", "-PT1M").forEach { value ->
            runner.withPropertyValues("access.grant-expiry-notice.interval=$value").run { assertThat(it).hasFailed() }
        }
    }

    @Test
    fun `a look-alike key binds nothing and fails to start`() {
        runner.withPropertyValues("access.grant-expiry-notices.interval=PT15M").run { assertThat(it).hasFailed() }
    }

    @Test
    fun `a positive interval binds`() {
        runner.withPropertyValues("access.grant-expiry-notice.interval=PT15M").run {
            assertThat(it.getBean(GrantExpiryNoticeProperties::class.java).interval).isEqualTo(Duration.ofMinutes(15))
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `the interval read from application yaml equals PT15M`() {
        val root = Yaml().load<Map<String, Any?>>(File("src/main/resources/application.yaml").readText())
        val section = (root["access"] as Map<String, Any?>)["grant-expiry-notice"] as Map<String, Any?>
        assertThat(Duration.parse(section["interval"] as String)).isEqualTo(Duration.ofMinutes(15))
    }

    @Test
    fun `the interval property is read through ConfigData from the real application yaml`() {
        ApplicationContextRunner()
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withUserConfiguration(OnlyProperties::class.java)
            .run {
                assertThat(
                    it.getBean(GrantExpiryNoticeProperties::class.java).interval,
                ).isEqualTo(Duration.ofMinutes(15))
            }
    }

    @Test
    fun `one unresolved recipient raises the counter by one with no tag and logs the grant reference and no address`() {
        val registry = SimpleMeterRegistry()
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        val logger = LoggerFactory.getLogger(GrantExpiryNoticeTelemetry::class.java) as Logger
        logger.addAppender(appender)
        try {
            GrantExpiryNoticeTelemetry(registry).recipientUnresolved("GRT-123")
            val counter = registry.get(GrantExpiryNoticeTelemetry.METRIC).counter()
            assertThat(counter.count()).isEqualTo(1.0)
            assertThat(counter.id.tags).isEmpty()
            val line = appender.list.single().formattedMessage
            assertThat(line).contains("GRT-123").doesNotContain("@")
        } finally {
            logger.detachAppender(appender)
        }
    }

    @Test
    fun `the telemetry source writes only the grant reference to its log line`() {
        val code = File("src/main/kotlin/com/dynokore/gst/access/bootstrap/GrantExpiryNoticeJob.kt").readText()
        val logCalls =
            Regex(
                """log\.(warn|info|error|debug)\(([^;\n]*)""",
            ).findAll(code).map { it.groupValues[2] }.toList()
        assertThat(logCalls.filter { it.contains("grant expiry notice recipient") }).allMatch {
            it.contains("grantReference") &&
                !Regex("(?i)address|email|username|name").containsMatchIn(it.replace("grantReference", ""))
        }
    }
}
