package com.dynokore.platform.serviceruntime.securityevents

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.dynokore.platform.serviceruntime.binding.TelemetryExport
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.slf4j.LoggerFactory
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.TimeUnit

/** INV-REQ-1384 / INV-Q-10939 / INV-T-10969: the OTLP record, its failure boundary. */
class SecurityEventEmitterTest {
    private val mapper = ObjectMapper()

    private fun properties(
        queue: Int = 10,
        attempts: Int = 3,
        classAttribute: String = "gst.security.class",
        allowed: List<String> = listOf("gst.security.class", "user.id", "gst.tenant.id"),
    ) = SecurityEventProperties(classAttribute, allowed, queue, attempts, 10, emptyMap())

    /** The ERROR lines the emitter writes while [block] runs. */
    private fun errorLines(block: () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(OtlpSecurityEventEmitter::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.filter { it.level.toString() == "ERROR" }.map { it.formattedMessage }
    }

    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < end) Thread.sleep(10)
    }

    private fun attributesOf(body: String): Map<String, String> {
        val record =
            mapper
                .readTree(body)
                .get("resourceLogs")
                .get(0)
                .get("scopeLogs")
                .get(0)
                .get("logRecords")
        record.size() shouldBe 1
        return record.get(0).get("attributes").associate {
            it.get("key").asString() to
                it.get("value").get("stringValue").asString()
        }
    }

    @ParameterizedTest
    @EnumSource(SecurityClass::class)
    fun `each class posts one record with exactly three attributes to v1 logs`(securityClass: SecurityClass) {
        CollectorStub { 200 }.use { stub ->
            OtlpSecurityEventEmitter(properties(), { TelemetryExport { stub.baseUrl } }, null).use { emitter ->
                emitter.emit(securityClass, "user-42", "tenant-7")
                await { stub.received.isNotEmpty() }
            }
            stub.received shouldHaveSize 1
            val request = stub.received.single()
            request.method shouldBe "POST"
            request.path shouldBe "/v1/logs"
            request.contentType shouldBe "application/json"
            attributesOf(request.body) shouldBe
                mapOf(
                    "gst.security.class" to securityClass.wireValue,
                    "user.id" to "user-42",
                    "gst.tenant.id" to "tenant-7",
                )
            val record =
                mapper
                    .readTree(
                        request.body,
                    ).get("resourceLogs")
                    .get(0)
                    .get("scopeLogs")
                    .get(0)
                    .get("logRecords")
                    .get(0)
            record.get("body").get("stringValue").asString() shouldBe securityClass.wireValue
            record.get("attributes").size() shouldBe 3
        }
    }

    @Test
    fun `an endpoint with a trailing slash still posts to v1 logs`() {
        CollectorStub { 200 }.use { stub ->
            OtlpSecurityEventEmitter(properties(), { TelemetryExport { stub.baseUrl + "/" } }, null).use {
                it.emit(SecurityClass.SIGN_IN, "u", "t")
                await { stub.received.isNotEmpty() }
            }
            stub.received.single().path shouldBe "/v1/logs"
        }
    }

    @Test
    fun `a sink that answers 200 after two refusals yields exactly one received record`() {
        CollectorStub { n -> if (n < 2) 503 else 200 }.use { stub ->
            val meters = SimpleMeterRegistry()
            OtlpSecurityEventEmitter(properties(), { TelemetryExport { stub.baseUrl } }, meters).use {
                it.emit(SecurityClass.BREAK_GLASS, "u", "t")
                await { stub.received.size >= 3 }
                Thread.sleep(200)
            }
            // three attempts reached the stub; the third was accepted, so no fourth attempt and no drop
            stub.received shouldHaveSize 3
            meters.find("gst_security_events_dropped_total").counter() shouldBe null
        }
    }

    @Test
    fun `a sink that refuses every request drops the record after the configured attempts and never throws`() {
        CollectorStub { 500 }.use { stub ->
            val meters = SimpleMeterRegistry()
            val lines =
                errorLines {
                    OtlpSecurityEventEmitter(
                        properties(attempts = 3),
                        { TelemetryExport { stub.baseUrl } },
                        meters,
                    ).use {
                        it.emit(SecurityClass.ELEVATION, "user-secret", "tenant-secret")
                        await { (meters.find("gst_security_events_dropped_total").counter()?.count() ?: 0.0) >= 1.0 }
                    }
                }
            lines shouldBe listOf("SECURITY_EVENT_DROPPED reason=RETRIES_EXHAUSTED")
            stub.received shouldHaveSize 3
            meters.get("gst_security_events_dropped_total").counter().count() shouldBe 1.0
        }
    }

    @Test
    fun `a sink that never answers never blocks emit and a full queue counts drops`() {
        CollectorStub { null }.use { stub ->
            val meters = SimpleMeterRegistry()
            val lines =
                errorLines {
                    OtlpSecurityEventEmitter(properties(queue = 2), { TelemetryExport { stub.baseUrl } }, meters).use {
                        val started = System.nanoTime()
                        repeat(20) { _ -> it.emit(SecurityClass.entries.first(), "user-secret", "tenant-secret") }
                        (System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2)) shouldBe true
                    }
                }
            lines.toSet() shouldBe setOf("SECURITY_EVENT_DROPPED reason=QUEUE_FULL")
            (meters.get("gst_security_events_dropped_total").counter().count() >= 17.0) shouldBe true
        }
    }

    @Test
    fun `a missing telemetry export drops the record and emit does not throw`() {
        val meters = SimpleMeterRegistry()
        OtlpSecurityEventEmitter(properties(attempts = 1), { null }, meters).use {
            it.emit(SecurityClass.SIGN_IN, "u", "t")
            await { (meters.find("gst_security_events_dropped_total").counter()?.count() ?: 0.0) >= 1.0 }
        }
        meters.get("gst_security_events_dropped_total").counter().count() shouldBe 1.0
    }

    @Test
    fun `a record whose attributes are not all allowed is dropped and not sent`() {
        CollectorStub { 200 }.use { stub ->
            val meters = SimpleMeterRegistry()
            val narrow = properties(allowed = listOf("gst.security.class", "user.id"))
            OtlpSecurityEventEmitter(narrow, { TelemetryExport { stub.baseUrl } }, meters).use {
                it.emit(SecurityClass.SIGN_IN, "u", "t")
                Thread.sleep(300)
            }
            stub.received shouldHaveSize 0
            meters.get("gst_security_events_dropped_total").counter().count() shouldBe 1.0
        }
    }

    @Test
    fun `the class attribute name is configuration`() {
        CollectorStub { 200 }.use { stub ->
            val renamed =
                properties(classAttribute = "x.class", allowed = listOf("x.class", "user.id", "gst.tenant.id"))
            OtlpSecurityEventEmitter(renamed, { TelemetryExport { stub.baseUrl } }, null).use {
                it.emit(SecurityClass.SIGN_IN, "u", "t")
                await { stub.received.isNotEmpty() }
            }
            attributesOf(stub.received.single().body).keys shouldBe setOf("x.class", "user.id", "gst.tenant.id")
        }
    }
}
