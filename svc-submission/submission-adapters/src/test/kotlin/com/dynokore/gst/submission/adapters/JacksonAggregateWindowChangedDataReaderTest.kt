package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.events.JacksonAggregateWindowChangedDataReader
import com.dynokore.gst.submission.application.completeness.WindowChangeState
import com.dynokore.platform.outboxandqueue.ContractViolation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** The AggregateWindowChanged reader (INV-AC-22206). */
class JacksonAggregateWindowChangedDataReaderTest {
    private val reader = JacksonAggregateWindowChangedDataReader()

    private fun json(
        window: String = "{\"from\":\"2026-04-01\",\"to\":\"2026-06-30\"}",
        state: String = "\"LATE\"",
        version: String? = "2",
        entity: String? = "\"E\"",
    ) = buildString {
        append("{")
        if (entity != null) append("\"entityRef\":$entity,")
        append("\"prescribedPeriod\":\"P\",\"window\":$window,\"state\":$state")
        if (version != null) append(",\"stateVersion\":$version")
        append("}")
    }

    @Test
    fun `INV-AC-22206 a payload with stateVersion is read`() {
        val d = reader.read(json())
        d.entityRef shouldBe "E"
        d.prescribedPeriod shouldBe "P"
        d.from shouldBe LocalDate.of(2026, 4, 1)
        d.to shouldBe LocalDate.of(2026, 6, 30)
        d.state shouldBe WindowChangeState.LATE
        d.stateVersion shouldBe 2L
    }

    @Test
    fun `INV-AC-22206 a payload without stateVersion is read as 0`() {
        reader.read(json(version = null, state = "\"OPEN\"")).let {
            it.stateVersion shouldBe 0L
            it.state shouldBe WindowChangeState.OPEN
        }
    }

    @Test
    fun `INV-AC-22206 a missing key member or a window without from or to is a ContractViolation`() {
        shouldThrow<ContractViolation> { reader.read(json(entity = null)) }
        shouldThrow<ContractViolation> { reader.read(json(window = "{\"to\":\"2026-06-30\"}")) }
        shouldThrow<ContractViolation> { reader.read(json(window = "{\"from\":\"2026-04-01\"}")) }
        shouldThrow<ContractViolation> { reader.read(json(window = "\"x\"")) }
        shouldThrow<ContractViolation> { reader.read(json(window = "{\"from\":\"nope\",\"to\":\"2026-06-30\"}")) }
        shouldThrow<ContractViolation> {
            reader.read(
                "{\"entityRef\":\"E\",\"prescribedPeriod\":\"P\",\"state\":\"OPEN\"}",
            )
        }
    }

    @Test
    fun `INV-AC-22206 a zero, negative, fractional or string stateVersion is a ContractViolation`() {
        listOf("0", "-1", "1.5", "\"3\"", "null").forEach {
            shouldThrow<ContractViolation> { reader.read(json(version = it)) }
        }
    }

    @Test
    fun `INV-AC-22206 an unknown state is a ContractViolation without the value`() {
        val e = shouldThrow<ContractViolation> { reader.read(json(state = "\"SECRET-STATE\"")) }
        e.message.orEmpty().contains("SECRET-STATE") shouldBe false
    }
}
