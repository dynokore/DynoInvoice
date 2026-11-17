package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.events.JacksonAcknowledgementReceivedDataReader
import com.dynokore.gst.submission.application.queue.AcknowledgementReceivedData
import com.dynokore.platform.outboxandqueue.ContractViolation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/** The `AcknowledgementReceived` data reader (INV-AC-24271), fakes only. */
class AcknowledgementReceivedReaderTest {
    private val reader = JacksonAcknowledgementReceivedDataReader()
    private val doc = "6f1c3f0e-6a52-4c53-9a39-5d2d3c1f0a11"

    private fun json(
        outcome: String? = null,
        docUuid: String? = "\"$doc\"",
    ): String =
        buildList {
            if (docUuid != null) add("\"docUuid\":$docUuid")
            add("\"version\":1")
            add("\"acknowledgementId\":\"ACK-1\"")
            add("\"apNotificationId\":\"N-1\"")
            if (outcome != null) add("\"outcome\":$outcome")
        }.joinToString(",", "{", "}")

    @Test
    fun `INV-AC-24271 an absent outcome reads ACCEPTED`() {
        reader.read(json()) shouldBe AcknowledgementReceivedData(doc, 1, "ACK-1", "ACCEPTED")
    }

    @Test
    fun `INV-AC-24271 REJECTED is read and ACCEPTED is read`() {
        reader.read(json("\"REJECTED\"")).outcome shouldBe "REJECTED"
        reader.read(json("\"ACCEPTED\"")).outcome shouldBe "ACCEPTED"
    }

    @Test
    fun `INV-AC-24271 an unknown or look-alike outcome throws a contract violation without the value`() {
        for (bad in listOf("\"MAYBE\"", "\"accepted\"", "\"ACCEPTED \"", "\"\"", "null", "1", "[\"ACCEPTED\"]")) {
            val e = shouldThrow<ContractViolation> { reader.read(json(bad)) }
            e.message.orEmpty() shouldNotContain "MAYBE"
        }
    }

    @Test
    fun `INV-AC-24271 a blank or missing docUuid throws`() {
        for (bad in listOf(null, "\"\"", "\"   \"", "null", "5")) {
            shouldThrow<ContractViolation> { reader.read(json(docUuid = bad)) }
        }
    }

    @Test
    fun `INV-AC-24271 a missing acknowledgementId, a bad version or malformed data throws`() {
        shouldThrow<ContractViolation> { reader.read("""{"docUuid":"$doc","version":1}""") }
        shouldThrow<ContractViolation> { reader.read("""{"docUuid":"$doc","version":1,"acknowledgementId":" "}""") }
        shouldThrow<ContractViolation> { reader.read("""{"docUuid":"$doc","version":0,"acknowledgementId":"A"}""") }
        shouldThrow<ContractViolation> { reader.read("""{"docUuid":"$doc","version":"1","acknowledgementId":"A"}""") }
        shouldThrow<ContractViolation> { reader.read("""{"docUuid":"$doc","version":1.5,"acknowledgementId":"A"}""") }
        shouldThrow<ContractViolation> { reader.read("not json") }
        shouldThrow<ContractViolation> { reader.read("[]") }
    }
}
