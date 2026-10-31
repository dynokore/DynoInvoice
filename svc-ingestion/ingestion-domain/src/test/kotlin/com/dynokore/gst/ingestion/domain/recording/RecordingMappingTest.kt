package com.dynokore.gst.ingestion.domain.recording

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

class RecordingMappingTest {
    private val docUuid = UUID.randomUUID()

    private fun rejected(
        code: String?,
        status: Int,
    ) = RecordingMapping.map(CanonicalAnswer.Rejected(code, status))

    @Test
    fun `ING-002 is a stale revision whatever the status`() {
        rejected("ING-002", 409) shouldBe RecordingOutcome.Stale
        RecordingOutcome.Stale.httpStatus shouldBe 409
    }

    @Test
    fun `a held answer keeps the Doc UUID, version and reasons in order and answers 202`() {
        val reasons = listOf("MDM-001", "ING-016", "RUL-001")
        val outcome = RecordingMapping.map(CanonicalAnswer.Held(docUuid, 4, reasons))
        outcome shouldBe RecordingOutcome.Held(docUuid, 4, reasons)
        outcome.httpStatus shouldBe 202
        (outcome as RecordingOutcome.Held).reasons shouldBe reasons
    }

    @Test
    fun `each of the five relay codes with a 4xx status is relayed with its status and code`() {
        listOf("ING-005", "ING-011", "ING-012", "ING-016", "ING-019").forEach { code ->
            listOf(400, 404, 422, 499).forEach { status ->
                val outcome = rejected(code, status)
                outcome shouldBe RecordingOutcome.Relayed(status, code)
                outcome.httpStatus shouldBe status
            }
        }
        RecordingMapping.RELAY_CODES shouldBe setOf("ING-005", "ING-011", "ING-012", "ING-016", "ING-019")
    }

    @Test
    fun `look-alikes and out-of-range statuses are Failed with 500`() {
        listOf("ing-011", "ING-0111", " ING-011", "ING-011 ", "ING-013", "PER-009", "", null).forEach {
            rejected(it, 422) shouldBe RecordingOutcome.Failed
        }
        listOf(500, 302, 399, 500, 200, 202, 0).forEach {
            rejected("ING-011", it) shouldBe RecordingOutcome.Failed
        }
        rejected(null, 409) shouldBe RecordingOutcome.Failed
        RecordingOutcome.Failed.httpStatus shouldBe 500
        RecordingOutcome.Failed.ERROR_CODE shouldBe "SRT-010"
    }

    @Test
    fun `version 1 is new and every higher version is an amendment`() {
        RecordingMapping.map(CanonicalAnswer.Created(docUuid, 1)) shouldBe RecordingOutcome.New(docUuid)
        RecordingMapping.map(CanonicalAnswer.Created(docUuid, 2)) shouldBe RecordingOutcome.Amend(docUuid, 2)
    }

    @Test
    fun `a replay keeps the Doc UUID and answers 200`() {
        val outcome = RecordingMapping.map(CanonicalAnswer.Replay(docUuid, 3))
        outcome shouldBe RecordingOutcome.Replay(docUuid)
        outcome.httpStatus shouldBe 200
    }

    @Test
    fun `INV-AC-14089 an Unavailable answer is 503 ING-023`() {
        val outcome = RecordingMapping.map(CanonicalAnswer.Unavailable("timed out"))
        outcome shouldBe RecordingOutcome.Unavailable
        outcome.httpStatus shouldBe 503
        RecordingOutcome.Unavailable.ERROR_CODE shouldBe "ING-023"
    }

    @Test
    fun `INV-AC-14089 a rejection with status 503 is Unavailable whatever the code`() {
        rejected(null, 503) shouldBe RecordingOutcome.Unavailable
        rejected("ING-005", 503) shouldBe RecordingOutcome.Unavailable
        rejected("SRT-010", 503) shouldBe RecordingOutcome.Unavailable
    }

    @Test
    fun `INV-AC-14089 ING-002 with status 503 stays Stale`() {
        rejected("ING-002", 503) shouldBe RecordingOutcome.Stale
    }

    @Test
    fun `INV-AC-14089 statuses 500, 502 and 504 with no code stay Failed`() {
        listOf(500, 502, 504).forEach { rejected(null, it) shouldBe RecordingOutcome.Failed }
    }

    @Test
    fun `INV-AC-14089 a relay code with status 400 is still relayed`() {
        rejected("ING-005", 400) shouldBe RecordingOutcome.Relayed(400, "ING-005")
    }

    @Test
    fun `INV-AC-14089 the Unavailable code is exactly ING-023`() {
        RecordingOutcome.Unavailable.ERROR_CODE shouldBe "ING-023"
        (RecordingOutcome.Unavailable.ERROR_CODE == "ING-022") shouldBe false
        (RecordingOutcome.Unavailable.ERROR_CODE == "ING-0023") shouldBe false
    }
}
