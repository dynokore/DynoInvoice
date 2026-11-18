package com.dynokore.gst.submission.application.ordering

import com.dynokore.gst.submission.application.drain.SafetyMarginAnswer
import com.dynokore.gst.submission.application.pack.PackMargin
import com.dynokore.gst.submission.application.queue.AcknowledgementBasis
import com.dynokore.gst.submission.application.queue.AcknowledgementInputs
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/** The release-alone instant (INV-AC-24521). */
class ReleaseAloneAtTest {
    private val t1 = TenantId(UUID.randomUUID())
    private val zone = ZoneId.of("Asia/Singapore")
    private val due = LocalDate.parse("2026-03-20")

    private fun known(
        value: Long,
        unit: String,
    ) = SafetyMarginAnswer.Known(PackMargin.of(value, unit)!!)

    private fun at(inputs: AcknowledgementInputs) = ReleaseAloneAt(inputs, zone).of(t1, "CN1")

    private fun warnings(block: () -> Unit): List<String> {
        val logger = Logger.getLogger(ReleaseAloneAt::class.java.name)
        val seen = mutableListOf<String>()
        val handler =
            object : Handler() {
                override fun publish(record: LogRecord) {
                    if (record.level == Level.WARNING) seen += record.message
                }

                override fun flush() = Unit

                override fun close() = Unit
            }
        logger.addHandler(handler)
        try {
            block()
        } finally {
            logger.removeHandler(handler)
        }
        return seen
    }

    @Test
    fun `INV-AC-24521 R1 two DAYS gives 2026-03-18T16 00Z`() {
        at { _, _ -> AcknowledgementBasis(due, known(2, "DAYS")) } shouldBe Instant.parse("2026-03-18T16:00:00Z")
    }

    @Test
    fun `INV-AC-24521 R2 six HOURS gives 2026-03-20T10 00Z`() {
        at { _, _ -> AcknowledgementBasis(due, known(6, "HOURS")) } shouldBe Instant.parse("2026-03-20T10:00:00Z")
    }

    @Test
    fun `INV-AC-24521 R3 a null due date gives null`() {
        at { _, _ -> AcknowledgementBasis(null, known(2, "DAYS")) } shouldBe null
    }

    @Test
    fun `INV-AC-24521 R4 a margin that is not Known gives null`() {
        at { _, _ -> AcknowledgementBasis(due, SafetyMarginAnswer.Unavailable) } shouldBe null
    }

    @Test
    fun `INV-AC-24521 R5 a RuntimeException of the inputs gives null and one WARNING with no value`() {
        var result: Instant? = Instant.EPOCH
        val lines = warnings { result = at { _, _ -> throw IllegalStateException("secret-value") } }
        result shouldBe null
        lines.size shouldBe 1
        lines.single().contains("secret-value") shouldBe false
    }

    @Test
    fun `INV-AC-24521 R6 an Error of the inputs propagates`() {
        shouldThrow<StackOverflowError> { at { _, _ -> throw StackOverflowError() } }
    }

    @Test
    fun `INV-AC-24521 R7 the tenant and Doc UUID are passed to the inputs`() {
        val seen = mutableListOf<Pair<TenantId, String>>()
        at { t, d ->
            seen += t to d
            AcknowledgementBasis(due, known(2, "DAYS"))
        }
        seen shouldBe listOf(t1 to "CN1")
    }
}
