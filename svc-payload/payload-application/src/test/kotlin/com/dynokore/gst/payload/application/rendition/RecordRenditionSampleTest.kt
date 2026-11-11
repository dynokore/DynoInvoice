package com.dynokore.gst.payload.application.rendition

import com.dynokore.gst.payload.domain.SampleCheckResult
import com.dynokore.gst.payload.domain.SampleResult
import com.dynokore.gst.payload.domain.SubmissionType
import com.dynokore.gst.payload.domain.WordingRequirement
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

private typealias Pair2 = Pair<String, SubmissionType>

private class Journal {
    val events = mutableListOf<String>()
}

private class FakeStore(
    private val journal: Journal,
) : RenditionSampleStore {
    val samples = mutableMapOf<List<Any>, SampleCheckResult>()
    var standing: Map<Pair2, SampleResult> = emptyMap()
    var saves = 0

    override fun findRecorded(
        entityRef: String,
        sampleSha256: String,
        scenario: String,
        submissionType: SubmissionType,
    ) = samples[listOf(entityRef, sampleSha256, scenario, submissionType)]

    override fun latest(entityRef: String) = standing

    override fun save(
        entityRef: String,
        sampleSha256: String,
        scenario: String,
        submissionType: SubmissionType,
        result: SampleCheckResult,
        latest: Map<Pair2, SampleResult>,
    ) {
        journal.events += "save"
        saves++
        samples[listOf(entityRef, sampleSha256, scenario, submissionType)] = result
        standing = latest
    }
}

private class FakeRecorder(
    private val journal: Journal,
) : EvidenceArtefactRecorder {
    val recorded = mutableListOf<EvidenceArtefact>()

    override fun record(
        artefact: EvidenceArtefact,
        context: EventContext,
    ) {
        journal.events += "emit"
        recorded += artefact
    }
}

class RecordRenditionSampleTest {
    private val journal = Journal()
    private val store = FakeStore(journal)
    private val recorder = FakeRecorder(journal)
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
    private var text: String? = "Tax Invoice GST Reg No"
    private val listed = setOf("S1" to SubmissionType.TYPE_1A, "S2" to SubmissionType.TYPE_1A)
    private val uow =
        object : RenditionUnitOfWork {
            override fun <T> run(block: () -> T): T {
                journal.events += "begin"
                val r = block()
                journal.events += "commit"
                return r
            }
        }
    private val useCase =
        RecordRenditionSample(
            { _, scenario, type ->
                if (scenario to type in
                    listed
                ) {
                    listOf(WordingRequirement("GST Reg No", "c1"), WordingRequirement("Zebra", "c2"))
                } else {
                    null
                }
            },
            { text },
            store,
            uow,
            RecordSampleCheckEvidence(recorder),
        )

    private fun cmd(
        scenario: String = "S1",
        type: SubmissionType = SubmissionType.TYPE_1A,
        sha: Char = 'a',
        checkId: String = "chk-1",
        by: String = "user-1",
    ) = RenditionSampleCommand("E-001", scenario, type, byteArrayOf(1), sha.toString().repeat(64), checkId, at, by, ctx)

    private fun recorded(o: RenditionSampleOutcome) = o as RenditionSampleOutcome.Recorded

    @Test
    fun `INV-AC-20322 listed pair with missing wording fails, opens the finding and emits one artefact`() {
        val o = recorded(useCase.record(cmd()))
        o.result.result shouldBe SampleResult.FAILED
        o.result.missingWording shouldBe listOf("Zebra")
        o.findingOpen shouldBe true
        o.findingChanged shouldBe true
        store.standing shouldBe mapOf(("S1" to SubmissionType.TYPE_1A) to SampleResult.FAILED)
        recorder.recorded.size shouldBe 1
        recorder.recorded[0].type shouldBe EvidenceArtefactType.SAMPLE_CHECK
        val expected =
            SampleCheckArtefact.canonicalBytes(
                "E-001",
                "chk-1",
                "S1",
                SubmissionType.TYPE_1A,
                SampleResult.FAILED,
                listOf("Zebra"),
                true,
                "a".repeat(64),
                at,
                "user-1",
            )
        recorder.recorded[0].hash shouldBe EvidenceArtefact.sha256Hex(expected)
    }

    @Test
    fun `INV-AC-20322 a later passed sample of the same pair closes the finding with a new reference`() {
        useCase.record(cmd(sha = 'a', checkId = "chk-1"))
        text = "GST Reg No Zebra"
        val o = recorded(useCase.record(cmd(sha = 'b', checkId = "chk-2")))
        o.result.result shouldBe SampleResult.PASSED
        o.findingOpen shouldBe false
        o.findingChanged shouldBe true
        recorder.recorded.size shouldBe 2
        (recorder.recorded[0].reference == recorder.recorded[1].reference) shouldBe false
    }

    @Test
    fun `INV-AC-20322 a failed pair stays open when another pair passes`() {
        useCase.record(cmd(scenario = "S1", sha = 'a', checkId = "chk-1"))
        text = "GST Reg No Zebra"
        val o = recorded(useCase.record(cmd(scenario = "S2", sha = 'b', checkId = "chk-2")))
        o.result.result shouldBe SampleResult.PASSED
        o.findingOpen shouldBe true
        o.findingChanged shouldBe false
    }

    @Test
    fun `INV-AC-20322 an unlisted pair returns NotListed and stores and emits nothing`() {
        useCase.record(cmd(scenario = "S9")) shouldBe RenditionSampleOutcome.NotListed
        useCase.record(cmd(type = SubmissionType.TYPE_2)) shouldBe RenditionSampleOutcome.NotListed
        store.saves shouldBe 0
        recorder.recorded.size shouldBe 0
    }

    @Test
    fun `INV-AC-20322 a repeat returns the stored result and emits nothing`() {
        val first = recorded(useCase.record(cmd()))
        text = "GST Reg No Zebra"
        val again = recorded(useCase.record(cmd(checkId = "chk-9")))
        again.result shouldBe first.result
        again.findingOpen shouldBe true
        again.findingChanged shouldBe false
        store.saves shouldBe 1
        recorder.recorded.size shouldBe 1
    }

    @Test
    fun `INV-AC-20322 a repeat after a later passed sample reports the closed standing and no change`() {
        val first = recorded(useCase.record(cmd(sha = 'a', checkId = "chk-1")))
        text = "GST Reg No Zebra"
        useCase.record(cmd(sha = 'b', checkId = "chk-2"))
        val again = recorded(useCase.record(cmd(sha = 'a', checkId = "chk-3")))
        again.result shouldBe first.result
        again.findingOpen shouldBe false
        again.findingChanged shouldBe false
        store.saves shouldBe 2
        recorder.recorded.size shouldBe 2
    }

    @Test
    fun `INV-AC-20322 the same hash under another scenario or submission type is a new sample`() {
        useCase.record(cmd(scenario = "S1"))
        useCase.record(cmd(scenario = "S2", checkId = "chk-2"))
        store.saves shouldBe 2
        recorder.recorded.size shouldBe 2
    }

    @Test
    fun `INV-AC-20322 a blank checkedBy is refused`() {
        assertThrows(IllegalArgumentException::class.java) { cmd(by = "  ") }
        store.saves shouldBe 0
        recorder.recorded.size shouldBe 0
    }

    @Test
    fun `INV-AC-20322 the unit of work wraps the store write and the emission`() {
        useCase.record(cmd())
        journal.events shouldBe listOf("begin", "save", "emit", "commit")
    }
}
