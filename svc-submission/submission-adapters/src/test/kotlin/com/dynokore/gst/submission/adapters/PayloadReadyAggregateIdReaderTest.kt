package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.events.JacksonPayloadReadyDataReader
import com.dynokore.platform.outboxandqueue.ContractViolation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.Base64

/** The optional aggregateId member of a PayloadReady (INV-AC-22807). */
class PayloadReadyAggregateIdReaderTest {
    private val reader = JacksonPayloadReadyDataReader()

    private fun json(aggregate: String?): String {
        val bytes = Base64.getEncoder().encodeToString(byteArrayOf(1))
        val member = if (aggregate == null) "" else ""","aggregateId":$aggregate"""
        return """{"docUuid":"3f2b8c1e-0000-4000-8000-000000000001","version":1,"submissionType":"TYPE_1A",""" +
            """"specificationVersion":"1","payloadBytes":"$bytes","payloadSha256":"x"$member}"""
    }

    private fun refused(aggregate: String) = shouldThrow<ContractViolation> { reader.read(json(aggregate)) }

    @Test
    fun `INV-AC-22807 an absent member gives null`() {
        reader.read(json(null)).aggregateId shouldBe null
    }

    @Test
    fun `INV-AC-22807 a string gives the string`() {
        reader.read(json("\"AGG-2026-0001\"")).aggregateId shouldBe "AGG-2026-0001"
    }

    @Test
    fun `INV-AC-22807 the number 12 is refused naming the member`() {
        refused("12").message shouldContain "aggregateId"
    }

    @Test
    fun `INV-AC-22807 the empty string is refused naming the member`() {
        refused("\"\"").message shouldContain "aggregateId"
    }

    @Test
    fun `INV-AC-22807 a JSON null is refused naming the member`() {
        refused("null").message shouldContain "aggregateId"
    }
}
