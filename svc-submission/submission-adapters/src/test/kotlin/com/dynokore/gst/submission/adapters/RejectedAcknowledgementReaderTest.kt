package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.events.JacksonAcknowledgementOutcomeReader
import com.dynokore.gst.submission.adapters.events.JacksonRejectedAcknowledgementReader
import com.dynokore.gst.submission.domain.transitions.RejectionReason
import com.dynokore.platform.outboxandqueue.ContractViolation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/** The two readers of the REJECTED outcome (INV-AC-24283), fakes only. */
class RejectedAcknowledgementReaderTest {
    private val rejected = JacksonRejectedAcknowledgementReader()
    private val outcome = JacksonAcknowledgementOutcomeReader()
    private val doc = "6f1c3f0e-6a52-4c53-9a39-5d2d3c1f0a11"

    private fun json(reasons: String) =
        """{"docUuid":"$doc","version":2,"acknowledgementId":"A","outcome":"REJECTED","reasons":$reasons}"""

    @Test
    fun `INV-AC-24283 the reasons are parsed in order with code and message`() {
        val data =
            rejected.read(
                json("""[{"code":"SUB-004-R1","message":"m1"},{"code":"SUB-005","message":"m2","extra":1}]"""),
            )
        data.docUuid shouldBe doc
        data.version shouldBe 2
        data.reasons shouldBe
            listOf(RejectionReason("SUB-004-R1", message = "m1"), RejectionReason("SUB-005", message = "m2"))
    }

    @Test
    fun `INV-AC-24283 an empty reasons list is read as empty`() {
        rejected.read(json("[]")).reasons shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24283 a missing or blank code or message throws without the value`() {
        for (bad in listOf(
            """[{"message":"m"}]""",
            """[{"code":"C"}]""",
            """[{"code":"","message":"m"}]""",
            """[{"code":"C","message":" "}]""",
            """[{"code":1,"message":"m"}]""",
            """[{"code":"C","message":null}]""",
            """["C"]""",
            """[{"code":"C","message":"m"},{"code":"D"}]""",
        )) {
            shouldThrow<ContractViolation> { rejected.read(json(bad)) }
        }
        shouldThrow<ContractViolation> { rejected.read(json("""[{"code":"SECRET","message":" "}]""")) }
            .message
            .orEmpty() shouldNotContain "SECRET"
    }

    @Test
    fun `INV-AC-24283 a missing reasons, docUuid or version and malformed data throw`() {
        shouldThrow<ContractViolation> { rejected.read("""{"docUuid":"$doc","version":1}""") }
        shouldThrow<ContractViolation> { rejected.read("""{"docUuid":"$doc","version":1,"reasons":{}}""") }
        shouldThrow<ContractViolation> { rejected.read("""{"version":1,"reasons":[]}""") }
        shouldThrow<ContractViolation> { rejected.read("""{"docUuid":"$doc","reasons":[]}""") }
        shouldThrow<ContractViolation> { rejected.read("""{"docUuid":"$doc","version":0,"reasons":[]}""") }
        shouldThrow<ContractViolation> { rejected.read("not json") }
        shouldThrow<ContractViolation> { rejected.read("[]") }
    }

    @Test
    fun `INV-AC-24283 an absent outcome reads as ACCEPTED and the two known values are read`() {
        outcome.outcomeOf("""{"docUuid":"$doc","version":1}""") shouldBe "ACCEPTED"
        outcome.outcomeOf("""{"outcome":"ACCEPTED"}""") shouldBe "ACCEPTED"
        outcome.outcomeOf("""{"outcome":"REJECTED"}""") shouldBe "REJECTED"
    }

    @Test
    fun `INV-AC-24283 an unknown or look-alike outcome throws without the value`() {
        for (bad in listOf("\"MAYBE\"", "\"accepted\"", "\"ACCEPTED \"", "\"\"", "null", "1", "[\"ACCEPTED\"]")) {
            val e = shouldThrow<ContractViolation> { outcome.outcomeOf("""{"outcome":$bad}""") }
            e.message.orEmpty() shouldNotContain "MAYBE"
        }
        shouldThrow<ContractViolation> { outcome.outcomeOf("not json") }
        shouldThrow<ContractViolation> { outcome.outcomeOf("[]") }
    }
}
