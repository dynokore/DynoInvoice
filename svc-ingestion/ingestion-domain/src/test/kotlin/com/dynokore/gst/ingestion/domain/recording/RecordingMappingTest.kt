package com.dynokore.gst.ingestion.domain.recording

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

class RecordingMappingTest {
    private val docUuid = UUID.randomUUID()

    @Test
    fun `only ING-002 is a stale revision and any other rejection is unmapped`() {
        RecordingMapping.map(CanonicalAnswer.Rejected("ING-002")) shouldBe RecordingOutcome.Stale
        listOf("ING-012", "ING-0021", "ing-002", "ING-002 ", "").forEach {
            shouldThrow<UnmappedCanonicalAnswer> { RecordingMapping.map(CanonicalAnswer.Rejected(it)) }
        }
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
}
