package com.dynokore.gst.payload.application.rendition

import com.dynokore.gst.payload.domain.SampleResult
import com.dynokore.gst.payload.domain.SubmissionType
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.serviceruntime.events.EvidenceArtefact
import com.dynokore.platform.serviceruntime.events.EvidenceArtefactRecorder
import com.dynokore.platform.serviceruntime.events.EvidenceArtefactType
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

private class AppendingRecorder : EvidenceArtefactRecorder {
    val recorded = mutableListOf<EvidenceArtefact>()

    override fun record(
        artefact: EvidenceArtefact,
        context: EventContext,
    ) {
        recorded += artefact
    }
}

class SampleCheckEvidenceTest {
    private val sha = "a".repeat(64)
    private val at = Instant.parse("2026-11-11T01:02:03Z")
    private val ctx =
        EventContext(
            TenantId(UUID.randomUUID()),
            EntityRef("E-001"),
            null,
            null,
            "sample-checks",
            null,
            CorrelationId("corr-1"),
            CausationId("cause-1"),
        )

    private fun bytes(
        checkId: String,
        result: SampleResult,
        missing: List<String>,
    ) = SampleCheckArtefact.canonicalBytes(
        "E-001",
        checkId,
        "STANDARD",
        SubmissionType.TYPE_1A,
        result,
        missing,
        true,
        sha,
        at,
        "user-7",
    )

    @Suppress("LongParameterList")
    private fun record(
        uc: RecordSampleCheckEvidence,
        checkId: String,
        result: SampleResult,
        missing: List<String> = emptyList(),
        by: String = "user-7",
        sampleSha: String = sha,
    ) = uc.record(
        "E-001",
        checkId,
        "STANDARD",
        SubmissionType.TYPE_1A,
        result,
        missing,
        true,
        sampleSha,
        at,
        by,
        ctx,
    )

    @Test
    fun `INV-AC-20307 two checks of one pair give two artefacts with distinct references and hashes`() {
        val rec = AppendingRecorder()
        val uc = RecordSampleCheckEvidence(rec)
        record(uc, "chk-1", SampleResult.PASSED)
        record(uc, "chk-2", SampleResult.FAILED, listOf("GST registration number"))
        rec.recorded.size shouldBe 2
        rec.recorded.map { it.type }.toSet() shouldBe setOf(EvidenceArtefactType.SAMPLE_CHECK)
        rec.recorded
            .map { it.reference }
            .toSet()
            .size shouldBe 2
        rec.recorded
            .map { it.hash }
            .toSet()
            .size shouldBe 2
        rec.recorded[0].reference shouldBe "E-001/chk-1"
    }

    @Test
    fun `INV-AC-20307 the FAILED result and the missing wording are inside the hashed bytes`() {
        val rec = AppendingRecorder()
        val uc = RecordSampleCheckEvidence(rec)
        record(uc, "chk-1", SampleResult.PASSED)
        record(uc, "chk-2", SampleResult.FAILED, listOf("GST registration number"))
        val b = bytes("chk-2", SampleResult.FAILED, listOf("GST registration number"))
        EvidenceArtefact.sha256Hex(b) shouldBe rec.recorded[1].hash
        val text = String(b, Charsets.UTF_8)
        (text.contains("\"result\":\"FAILED\"")) shouldBe true
        (text.contains("\"missingWording\":[\"GST registration number\"]")) shouldBe true
        text.startsWith("{\"checkId\":\"chk-2\",\"checkedAt\"") shouldBe true
    }

    @Test
    fun `INV-AC-20307 the hash is 64 lowercase hex and the same inputs give the same hash`() {
        val rec = AppendingRecorder()
        val uc = RecordSampleCheckEvidence(rec)
        record(uc, "chk-1", SampleResult.PASSED)
        record(uc, "chk-1", SampleResult.PASSED)
        Regex("^[0-9a-f]{64}$").matches(rec.recorded[0].hash) shouldBe true
        rec.recorded[0] shouldBe rec.recorded[1]
    }

    @Test
    fun `INV-AC-20307 a checkId differing only in case is a different reference`() {
        val rec = AppendingRecorder()
        val uc = RecordSampleCheckEvidence(rec)
        record(uc, "chk-a", SampleResult.PASSED)
        record(uc, "CHK-A", SampleResult.PASSED)
        (rec.recorded[0].reference == rec.recorded[1].reference) shouldBe false
    }

    @Test
    fun `INV-AC-20307 blank checkedBy checkId or entityRef and a 63 character sample hash are refused`() {
        val rec = AppendingRecorder()
        val uc = RecordSampleCheckEvidence(rec)
        assertThrows(IllegalArgumentException::class.java) { record(uc, "chk-1", SampleResult.PASSED, by = " ") }
        assertThrows(IllegalArgumentException::class.java) { record(uc, "", SampleResult.PASSED) }
        val short = "a".repeat(63)
        val upper = "A".repeat(64)
        assertThrows(
            IllegalArgumentException::class.java,
        ) { record(uc, "chk-1", SampleResult.PASSED, sampleSha = short) }
        assertThrows(
            IllegalArgumentException::class.java,
        ) { record(uc, "chk-1", SampleResult.PASSED, sampleSha = upper) }
        assertThrows(IllegalArgumentException::class.java) {
            SampleCheckArtefact.of(
                " ",
                "c",
                "s",
                SubmissionType.TYPE_1A,
                SampleResult.PASSED,
                emptyList(),
                true,
                sha,
                at,
                "u",
            )
        }
        rec.recorded.size shouldBe 0
    }
}
