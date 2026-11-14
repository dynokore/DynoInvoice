package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.events.JacksonFeedHealthChangedDataReader
import com.dynokore.gst.submission.application.completeness.FeedHealthState
import com.dynokore.platform.outboxandqueue.ContractViolation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The FeedHealthChanged reader (INV-AC-22206). */
class JacksonFeedHealthChangedDataReaderTest {
    private val reader = JacksonFeedHealthChangedDataReader()

    private fun json(
        state: String = "\"FAILED\"",
        version: String? = "3",
        drop: String? = null,
    ) = buildString {
        append("{")
        val members =
            mutableListOf(
                "\"feed\":\"F1\"",
                "\"entityRef\":\"E\"",
                "\"period\":\"P\"",
                "\"control\":\"COUNT\"",
                "\"state\":$state",
            )
        if (version != null) members += "\"stateVersion\":$version"
        append(members.filter { drop == null || !it.startsWith("\"$drop\"") }.joinToString(","))
        append("}")
    }

    @Test
    fun `INV-AC-22206 a payload with stateVersion is read`() {
        val d = reader.read(json())
        d.feed shouldBe "F1"
        d.entityRef shouldBe "E"
        d.period shouldBe "P"
        d.control shouldBe "COUNT"
        d.state shouldBe FeedHealthState.FAILED
        d.stateVersion shouldBe 3L
    }

    @Test
    fun `INV-AC-22206 a payload without stateVersion is read as 0`() {
        reader.read(json(version = null, state = "\"CLEARED\"")).let {
            it.stateVersion shouldBe 0L
            it.state shouldBe FeedHealthState.CLEARED
        }
    }

    @Test
    fun `INV-AC-22206 a missing key member is a ContractViolation`() {
        listOf("feed", "entityRef", "period", "control", "state").forEach { name ->
            shouldThrow<ContractViolation> { reader.read(json(drop = name)) }
        }
    }

    @Test
    fun `INV-AC-22206 a zero, negative, fractional or string stateVersion is a ContractViolation`() {
        listOf("0", "-1", "1.5", "\"3\"", "null", "true").forEach {
            shouldThrow<ContractViolation> { reader.read(json(version = it)) }
        }
    }

    @Test
    fun `INV-AC-22206 an unknown state and non-object data are a ContractViolation without the value`() {
        val e = shouldThrow<ContractViolation> { reader.read(json(state = "\"SECRET-STATE\"")) }
        e.message.orEmpty().contains("SECRET-STATE") shouldBe false
        shouldThrow<ContractViolation> { reader.read("[]") }
        shouldThrow<ContractViolation> { reader.read("{") }
    }
}
