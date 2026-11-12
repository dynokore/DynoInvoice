package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.events.JacksonDocumentHeldDataReader
import com.dynokore.gst.submission.adapters.events.JacksonDocumentReleasedDataReader
import com.dynokore.platform.outboxandqueue.ContractViolation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** The two Jackson readers of the provisional entry consumers (INV-AC-20691 and the cases beyond it). */
class JacksonDocumentHeldDataReaderTest {
    private val held = JacksonDocumentHeldDataReader()
    private val released = JacksonDocumentReleasedDataReader()

    private fun heldJson(
        docsequence: String = "1",
        reasons: String = """["ING-016"]""",
        extra: String = "",
    ) = """{"docUuid":"D1","docsequence":$docsequence,"holdReasons":$reasons,"entityRef":"E1","direction":"SALE",
        |"documentDate":"2026-06-20","prescribedPeriodRef":"2026-06"$extra}
        """.trimMargin()

    @Test
    fun `INV-AC-20691 the posting date is the date part as written, null when absent`() {
        held.read(heldJson(extra = ""","postingTimestamp":"2026-07-02T23:30:00+08:00"""")).postingDate shouldBe
            LocalDate.of(2026, 7, 2)
        held.read(heldJson(extra = ""","postingTimestamp":"2026-07-02T23:30:00"""")).postingDate shouldBe
            LocalDate.of(2026, 7, 2)
        held.read(heldJson()).postingDate shouldBe null
    }

    @Test
    fun `INV-AC-20691 malformed held payloads throw a ContractViolation without any field value`() {
        listOf(
            heldJson(extra = ""","postingTimestamp":"SECRET-9""""),
            """{"docUuid":"SECRET-9","holdReasons":["ING-016"],"entityRef":"E1","direction":"SALE",""" +
                """"documentDate":"2026-06-20","prescribedPeriodRef":"2026-06"}""",
            heldJson(docsequence = "0"),
            heldJson(docsequence = "\"SECRET-9\""),
            heldJson(reasons = "[]"),
            heldJson(extra = ""","invoiceIssuedOrReceived":"SECRET-9""""),
            "[]",
            "not json",
        ).forEach {
            shouldThrow<ContractViolation> { held.read(it) }.message!! shouldNotContain "SECRET-9"
        }
    }

    @Test
    fun `the held reader ignores an unknown member and reads the optional flag`() {
        val data = held.read(heldJson(extra = ""","unknown":{"a":1},"invoiceIssuedOrReceived":true"""))
        data.docUuid shouldBe "D1"
        data.docsequence shouldBe 1L
        data.holdReasons shouldBe listOf("ING-016")
        data.documentDate shouldBe LocalDate.of(2026, 6, 20)
        data.invoiceIssuedOrReceived shouldBe true
    }

    @Test
    fun `the released reader reads docUuid and docsequence and rejects malformed payloads`() {
        val ok = released.read("""{"docUuid":"D1","docsequence":3,"releasedHoldReasons":["ING-016"],"x":1}""")
        ok.docUuid shouldBe "D1"
        ok.docsequence shouldBe 3L
        listOf(
            """{"docUuid":"D1","releasedHoldReasons":[]}""",
            """{"docUuid":"D1","docsequence":0,"releasedHoldReasons":[]}""",
            """{"docsequence":1,"releasedHoldReasons":[]}""",
            """{"docUuid":"D1","docsequence":1}""",
        ).forEach { shouldThrow<ContractViolation> { released.read(it) } }
    }
}
