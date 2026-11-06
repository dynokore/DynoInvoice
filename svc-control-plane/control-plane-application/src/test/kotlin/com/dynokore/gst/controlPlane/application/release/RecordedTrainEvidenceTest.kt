package com.dynokore.gst.controlPlane.application.release

import com.dynokore.gst.controlPlane.application.pack.Refusal
import com.dynokore.gst.controlPlane.application.pack.RequestContext
import com.dynokore.gst.controlPlane.domain.pack.ActorId
import com.dynokore.gst.controlPlane.domain.release.AccreditationCheck
import com.dynokore.gst.controlPlane.domain.release.Gate
import com.dynokore.gst.controlPlane.domain.release.GateOutcome
import com.dynokore.gst.controlPlane.domain.release.GateRunRecord
import com.dynokore.gst.controlPlane.domain.release.Observation
import com.dynokore.gst.controlPlane.domain.release.RecordedRegeneration
import com.dynokore.gst.controlPlane.domain.release.ReferenceEnvironment
import com.dynokore.gst.controlPlane.domain.release.ReleaseTrain
import com.dynokore.gst.controlPlane.domain.release.TrainKind
import com.dynokore.gst.controlPlane.domain.release.TrainVersion
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** The production binding of [TrainEvidenceSource] over the recorded gate runs (INV-T-8749, INV-Q-8717 A). */
class RecordedTrainEvidenceTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-12T08:45:00Z"), ZoneOffset.UTC)
    private val runs = MemoryGateRuns()
    private val source = RecordedTrainEvidence(runs)
    private val version = TrainVersion("2026.10.0")
    private val saas = ReferenceEnvironment.SAAS
    private val private = ReferenceEnvironment.PRIVATE_MINIMAL

    private fun record(
        gate: Gate,
        environment: ReferenceEnvironment?,
        candidate: GateOutcome = GateOutcome.PASSED,
        train: TrainVersion = version,
    ) = GateRunRecord(
        train,
        gate,
        environment,
        candidate,
        null,
        emptySet(),
        emptySet(),
        null,
        ActorId("rita"),
        clock.instant(),
    )

    private fun failed(
        gate: Gate,
        environment: ReferenceEnvironment?,
        findings: Set<String>,
        baseline: GateOutcome? = null,
        baselineFindings: Set<String> = emptySet(),
    ) = record(gate, environment, GateOutcome.FAILED).copy(
        candidateFindings = findings,
        baseline = baseline,
        baselineFindings = baselineFindings,
    )

    private fun all(train: TrainVersion = version) =
        listOf(
            record(Gate.SCENARIOS, saas, train = train),
            record(Gate.SCENARIOS, private, train = train),
            record(Gate.CERTIFICATION, saas, train = train),
            record(Gate.CERTIFICATION, private, train = train),
            record(Gate.SECURITY, null, train = train),
            record(Gate.REGENERATION, null, train = train),
        )

    private val trains = HashMap<TrainVersion, ReleaseTrain>()
    private val publish =
        PublishTrain(
            object : TrainRepository {
                override fun find(version: TrainVersion) = trains[version]

                override fun save(train: ReleaseTrain) {
                    trains[train.version] = train
                }
            },
            { _, key -> key == ReleasePermission.PUBLISH },
            source,
            { AccreditationCheck(false, null, null) },
            clock,
        )

    private fun publishCommand(exposureReducing: Boolean = false) =
        PublishTrainCommand(
            ActorId("rita"),
            version,
            TrainKind.TRAIN,
            exposureReducing,
            false,
            RequestContext("corr", "cause"),
        )

    private fun refusal(exposureReducing: Boolean = false): Refusal? {
        val command = publishCommand(exposureReducing)
        return publish.unmetPrecondition(command, publish.load(command))?.let(Refusal::parse)
    }

    @Test
    fun `with the six records a train has evidence and each of the six missing alone returns null`() {
        source.forTrain(version).shouldBeNull()
        runs.rows += all()
        source.forTrain(version).shouldNotBeNull()
        all().indices.forEach { missing ->
            runs.rows.clear()
            runs.rows += all().filterIndexed { i, _ -> i != missing }
            source.forTrain(version).shouldBeNull()
        }
    }

    @Test
    fun `a record of another train or a look-alike version never counts`() {
        runs.rows += all(TrainVersion("2026.10.01"))
        runs.rows += all(TrainVersion("2026.10.00"))
        source.forTrain(version).shouldBeNull()
        runs.rows += all(TrainVersion("2026.10.0 "))
        source.forTrain(version).shouldBeNull()
        // a store that matched by prefix would hand these over; the binding keeps only the exact version
        val greedy =
            object : GateRunRepository by runs {
                override fun forTrain(version: TrainVersion) =
                    runs.rows.filter { it.trainVersion.value.startsWith(version.value) }
            }
        RecordedTrainEvidence(greedy).forTrain(version).shouldBeNull()
    }

    @Test
    fun `the scenario result is passed only when both environments passed and its findings are the union`() {
        runs.rows += all().filter { it.gate != Gate.SCENARIOS }
        runs.rows += failed(Gate.SCENARIOS, saas, setOf("S-1"))
        runs.rows += record(Gate.SCENARIOS, private).copy(candidateFindings = setOf("S-2"))
        val scenarios = source.forTrain(version)!!.scenarios
        scenarios.candidate shouldBe GateOutcome.FAILED
        scenarios.findings shouldBe setOf("S-1", "S-2")
        scenarios.baseline.shouldBeNull()

        runs.rows.clear()
        runs.rows += all()
        source.forTrain(version)!!.scenarios.candidate shouldBe GateOutcome.PASSED
    }

    @Test
    fun `the scenario baseline is absent, FAILED when any holds FAILED, else PASSED, with the union of findings`() {
        runs.rows += all().filter { it.gate != Gate.SCENARIOS }
        runs.rows += failed(Gate.SCENARIOS, saas, setOf("S-1"), GateOutcome.PASSED, setOf("B-1"))
        runs.rows += failed(Gate.SCENARIOS, private, setOf("S-1"))
        source.forTrain(version)!!.scenarios.baseline shouldBe GateOutcome.PASSED
        source.forTrain(version)!!.scenarios.baselineFindings shouldBe setOf("B-1")
        runs.rows.removeAll { it.gate == Gate.SCENARIOS && it.environment == private }
        runs.rows += failed(Gate.SCENARIOS, private, setOf("S-1"), GateOutcome.FAILED, setOf("B-2"))
        source.forTrain(version)!!.scenarios.baseline shouldBe GateOutcome.FAILED
        source.forTrain(version)!!.scenarios.baselineFindings shouldBe setOf("B-1", "B-2")
    }

    private fun withCertificationBaselines(
        saasBaseline: GateOutcome?,
        privateBaseline: GateOutcome?,
    ) {
        runs.rows += all().filter { it.gate != Gate.CERTIFICATION }
        runs.rows += record(Gate.CERTIFICATION, saas).copy(baseline = saasBaseline)
        runs.rows += record(Gate.CERTIFICATION, private).copy(baseline = privateBaseline)
    }

    @Test
    fun `a baseline on the SAAS certification record only leaves releasedCertification unknown`() {
        withCertificationBaselines(GateOutcome.PASSED, null)
        val evidence = source.forTrain(version)!!
        evidence.releasedCertification.shouldBeNull()
        evidence.certification.results shouldBe mapOf(saas to GateOutcome.PASSED, private to GateOutcome.PASSED)
    }

    @Test
    fun `a baseline on the PRIVATE_MINIMAL certification record only leaves releasedCertification unknown`() {
        withCertificationBaselines(null, GateOutcome.FAILED)
        source.forTrain(version)!!.releasedCertification.shouldBeNull()
    }

    @Test
    fun `both certification baselines build releasedCertification with those two results`() {
        withCertificationBaselines(GateOutcome.PASSED, GateOutcome.FAILED)
        source.forTrain(version)!!.releasedCertification!!.results shouldBe
            mapOf(saas to GateOutcome.PASSED, private to GateOutcome.FAILED)
    }

    @Test
    fun `the certification result of an environment is its record's candidate result`() {
        runs.rows += all().filter { it.gate != Gate.CERTIFICATION }
        runs.rows += record(Gate.CERTIFICATION, saas)
        runs.rows += record(Gate.CERTIFICATION, private, GateOutcome.FAILED)
        val results = source.forTrain(version)!!.certification.results
        results shouldBe mapOf(saas to GateOutcome.PASSED, private to GateOutcome.FAILED)
    }

    @Test
    fun `the regeneration record carries the result and the differing documents without classification maps`() {
        runs.rows += all().filter { it.gate != Gate.REGENERATION }
        runs.rows += failed(Gate.REGENERATION, null, setOf("D-7"))
        val regeneration = source.forTrain(version)!!.regeneration
        regeneration shouldBe RecordedRegeneration(Observation(GateOutcome.FAILED, setOf("D-7")))
        refusal()!!.detail.contains("REGENERATION FAILED introduced=[D-7]") shouldBe true
    }

    @Test
    fun `INV-AC-1943 with the six records of a certified train publishTrain publishes it`() {
        runs.rows += all()
        refusal() shouldBe null
        publish
            .perform(publishCommand(), null)
            .record.classifiedBy.value shouldBe "rita"
    }

    @Test
    fun `INV-AC-1943 with the PRIVATE_MINIMAL certification record absent the train is refused`() {
        runs.rows += all().filterNot { it.gate == Gate.CERTIFICATION && it.environment == private }
        refusal()!!.code shouldBe Refusal.NO_GATE_EVIDENCE
    }

    @Test
    fun `INV-AC-1943 with the PRIVATE_MINIMAL certification record FAILED the train is refused`() {
        runs.rows += all().filterNot { it.gate == Gate.CERTIFICATION && it.environment == private }
        runs.rows += record(Gate.CERTIFICATION, private, GateOutcome.FAILED)
        val refused = refusal()!!
        refused.code shouldBe Refusal.GATE_FAILED
        refused.detail.contains("CERTIFICATION") shouldBe true
        refused.detail.contains("PRIVATE_MINIMAL") shouldBe true
    }

    @Test
    fun `INV-AC-2658 a new security finding blocks an exposure-reducing train and an old one does not`() {
        runs.rows += all().filter { it.gate != Gate.SECURITY }
        runs.rows += failed(Gate.SECURITY, null, setOf("F-9"), GateOutcome.FAILED, setOf("F-1"))
        val blocked = refusal(exposureReducing = true)!!
        blocked.code shouldBe Refusal.GATE_FAILED
        blocked.detail.contains("introduced=[F-9]") shouldBe true

        runs.rows.removeAll { it.gate == Gate.SECURITY }
        runs.rows += failed(Gate.SECURITY, null, setOf("F-9"), GateOutcome.FAILED, setOf("F-9"))
        refusal(exposureReducing = true) shouldBe null
        refusal(exposureReducing = false)!!.code shouldBe Refusal.GATE_FAILED
    }
}
