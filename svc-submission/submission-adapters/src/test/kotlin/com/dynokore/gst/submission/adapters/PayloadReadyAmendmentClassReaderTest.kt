package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.events.JacksonPayloadReadyDataReader
import com.dynokore.platform.outboxandqueue.ContractViolation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.Base64

/** The optional amendmentClass member of a PayloadReady (INV-AC-26664). */
class PayloadReadyAmendmentClassReaderTest {
    private val reader = JacksonPayloadReadyDataReader()

    private fun json(member: String?): String {
        val bytes = Base64.getEncoder().encodeToString(byteArrayOf(1))
        val extra = if (member == null) "" else ""","amendmentClass":$member"""
        return """{"docUuid":"3f2b8c1e-0000-4000-8000-000000000001","version":2,"submissionType":"TYPE_1A",""" +
            """"specificationVersion":"1","payloadBytes":"$bytes","payloadSha256":"x"$extra}"""
    }

    private fun refused(member: String) = shouldThrow<ContractViolation> { reader.read(json(member)) }

    @Test
    fun `INV-AC-26664 an absent member gives null`() {
        reader.read(json(null)).amendmentClass shouldBe null
    }

    @Test
    fun `INV-AC-26664 each of the five names gives the same text`() {
        listOf("NON_MONETARY", "IDENTITY", "PERIOD_SHIFTING", "MONETARY", "STRUCTURAL").forEach {
            reader.read(json("\"$it\"")).amendmentClass shouldBe it
        }
    }

    @Test
    fun `INV-AC-26664 look-alikes and wrong types are refused naming the member`() {
        listOf("\"MONETARY \"", "\"monetary\"", "\"UNKNOWN\"", "\"\"", "5", "null").forEach {
            refused(it).message shouldContain "amendmentClass"
        }
    }
}
