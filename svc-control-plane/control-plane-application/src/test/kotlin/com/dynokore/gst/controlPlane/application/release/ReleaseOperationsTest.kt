package com.dynokore.gst.controlPlane.application.release

import com.dynokore.gst.controlPlane.application.pack.Refusal
import com.dynokore.gst.controlPlane.application.pack.RequestContext
import com.dynokore.gst.controlPlane.domain.pack.ActorId
import com.dynokore.gst.controlPlane.domain.release.AccreditationCheck
import com.dynokore.gst.controlPlane.domain.release.Certification
import com.dynokore.gst.controlPlane.domain.release.DocumentId
import com.dynokore.gst.controlPlane.domain.release.Gate
import com.dynokore.gst.controlPlane.domain.release.GateOutcome
import com.dynokore.gst.controlPlane.domain.release.Observation
import com.dynokore.gst.controlPlane.domain.release.ReferenceEnvironment
import com.dynokore.gst.controlPlane.domain.release.RegenerationObservation
import com.dynokore.gst.controlPlane.domain.release.ReleaseTrain
import com.dynokore.gst.controlPlane.domain.release.SupportWindow
import com.dynokore.gst.controlPlane.domain.release.TrainEvidence
import com.dynokore.gst.controlPlane.domain.release.TrainKind
import com.dynokore.gst.controlPlane.domain.release.TrainVersion
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ReleaseOperationsTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-12T08:45:00Z"), ZoneOffset.UTC)
    private val request = RequestContext("corr-1", "cause-1")
    private val trains = LinkedHashMap<TrainVersion, ReleaseTrain>()
    private val windows = LinkedHashMap<String, SupportWindow>()
    private val evidence = HashMap<TrainVersion, TrainEvidence>()
    private val opened = ArrayList<TrainVersion>()
    private val permissions =
        OperatorPermissions { actor, key -> key == ReleasePermission.PUBLISH && actor in setOf("rita", "sam") }
    private val trainRepository =
        object : TrainRepository {
            override fun find(version: TrainVersion) = trains[version]

            override fun save(train: ReleaseTrain) {
                trains[train.version] = train
            }
        }
    private val windowRepository =
        object : SupportWindowRepository {
            override fun find(windowVersion: String) = windows[windowVersion]

            override fun save(window: SupportWindow) {
                windows[window.windowVersion] = window
            }
        }
    private val tasks =
        AccreditationTasks { version ->
            opened += version
            AccreditationCheck(true, "ALERT-$version", "TASK-$version")
        }
    private val publish = PublishTrain(trainRepository, permissions, { evidence[it] }, tasks, clock)
    private val setWindow = SetSupportWindow(windowRepository, permissions)

    private val bothCertified =
        Certification(ReferenceEnvironment.entries.associateWith { GateOutcome.PASSED })
    private val saasOnly = Certification(mapOf(ReferenceEnvironment.SAAS to GateOutcome.PASSED))
    private val passing = Observation(GateOutcome.PASSED)
    private val corpus = mapOf(DocumentId("D1") to "SR", DocumentId("D2") to "ZR")

    private fun evidenceOf(
        certification: Certification = bothCertified,
        security: Observation = passing,
        regeneration: RegenerationObservation = RegenerationObservation(corpus, corpus),
        released: Certification? = null,
    ) = TrainEvidence(passing, security, regeneration, certification, released)

    private fun publishAs(
        actor: String,
        version: String,
        exposureReducing: Boolean = false,
        kind: TrainKind = TrainKind.TRAIN,
    ): String? {
        val command =
            PublishTrainCommand(ActorId(actor), TrainVersion(version), kind, exposureReducing, true, request)
        check(publish.permits(actor, command)) { "not permitted" }
        val state = publish.load(command)
        if (publish.priorResult(command, state) != null) return null
        return publish.unmetPrecondition(command, state) ?: run {
            publish.perform(command, state)
            null
        }
    }

    @Test
    fun `INV-AC-1943 a train certified in the SaaS topology only is refused until the private profile passes`() {
        evidence[TrainVersion("2026.10.0")] = evidenceOf(certification = saasOnly)
        val refusal = Refusal.parse(publishAs("rita", "2026.10.0")!!)
        refusal.code shouldBe Refusal.GATE_FAILED
        refusal.detail.contains("CERTIFICATION") shouldBe true
        refusal.detail.contains("introduced=[PRIVATE_MINIMAL]") shouldBe true // the environment it is not certified in
        trains shouldBe emptyMap()
        evidence[TrainVersion("2026.10.0")] = evidenceOf(certification = bothCertified)
        publishAs("rita", "2026.10.0") shouldBe null
        trains
            .getValue(TrainVersion("2026.10.0"))
            .record.classifiedBy.value shouldBe "rita"
    }

    @Test
    fun `INV-AC-1943 a train with no certification result in either environment is refused`() {
        evidence[TrainVersion("2026.10.0")] = evidenceOf(certification = Certification(emptyMap()))
        Refusal.parse(publishAs("rita", "2026.10.0")!!).code shouldBe Refusal.GATE_FAILED
    }

    @Test
    fun `publishTrain with no gate evidence bound is refused and a caller without the permission is not permitted`() {
        Refusal.parse(publishAs("rita", "2026.10.0")!!).code shouldBe Refusal.NO_GATE_EVIDENCE
        val command =
            PublishTrainCommand(ActorId("bob"), TrainVersion("2026.10.0"), TrainKind.TRAIN, false, true, request)
        publish.permits("bob", command) shouldBe false
        publish.permits("rita", command.copy(actor = ActorId("bob"))) shouldBe false
    }

    @Test
    fun `two callers that hold the permission publish two trains and each classifiedBy is its caller's user ID`() {
        evidence[TrainVersion("2026.10.0")] = evidenceOf()
        evidence[TrainVersion("2026.10.1")] = evidenceOf()
        publishAs("rita", "2026.10.0") shouldBe null
        publishAs("sam", "2026.10.1") shouldBe null
        trains
            .getValue(TrainVersion("2026.10.0"))
            .record.classifiedBy.value shouldBe "rita"
        trains
            .getValue(TrainVersion("2026.10.1"))
            .record.classifiedBy.value shouldBe "sam"
    }

    @Test
    fun `INV-AC-2658 an exposure-reducing patch train passes an old finding and is blocked by a new one`() {
        val released = Observation(GateOutcome.FAILED, setOf("CVE-1"), GateOutcome.FAILED, setOf("CVE-1"))
        evidence[TrainVersion("2026.10.1")] = evidenceOf(security = released)
        publishAs("rita", "2026.10.1", exposureReducing = true, kind = TrainKind.PATCH) shouldBe null
        val run =
            trains
                .getValue(TrainVersion("2026.10.1"))
                .record.runs
                .single { it.gate == Gate.SECURITY }
        run.baseline shouldBe GateOutcome.FAILED // both scan results are recorded with the release
        run.candidate shouldBe GateOutcome.FAILED
        run.findings shouldBe setOf("CVE-1")
        trains
            .getValue(TrainVersion("2026.10.1"))
            .record.classifiedBy.value shouldBe "rita"

        val added = Observation(GateOutcome.FAILED, setOf("CVE-1", "CVE-2"), GateOutcome.FAILED, setOf("CVE-1"))
        evidence[TrainVersion("2026.10.2")] = evidenceOf(security = added)
        val refusal = Refusal.parse(publishAs("rita", "2026.10.2", exposureReducing = true, kind = TrainKind.PATCH)!!)
        refusal.code shouldBe Refusal.GATE_FAILED
        refusal.detail.contains("introduced=[CVE-2]") shouldBe true
        refusal.detail.contains("CVE-1") shouldBe false
        trains.containsKey(TrainVersion("2026.10.2")) shouldBe false
    }

    @Test
    fun `INV-AC-2658 a train that does not only reduce exposure is blocked by the old finding too`() {
        val old = Observation(GateOutcome.FAILED, setOf("CVE-1"), GateOutcome.FAILED, setOf("CVE-1"))
        evidence[TrainVersion("2026.10.1")] = evidenceOf(security = old)
        Refusal.parse(publishAs("rita", "2026.10.1", exposureReducing = false)!!).code shouldBe Refusal.GATE_FAILED
    }

    @Test
    fun `an exposure-reducing train whose failing gate has no re-run against the released version is blocked`() {
        evidence[TrainVersion("2026.10.1")] = evidenceOf(security = Observation(GateOutcome.FAILED, setOf("CVE-1")))
        Refusal.parse(publishAs("rita", "2026.10.1", exposureReducing = true)!!).code shouldBe Refusal.GATE_FAILED
    }

    @Test
    fun `an exposure-reducing train failing a gate its released version passes is blocked`() {
        val regressed = Observation(GateOutcome.FAILED, setOf("CVE-1"), GateOutcome.PASSED)
        evidence[TrainVersion("2026.10.1")] = evidenceOf(security = regressed)
        Refusal.parse(publishAs("rita", "2026.10.1", exposureReducing = true)!!).code shouldBe Refusal.GATE_FAILED
    }

    @Test
    fun `INV-AC-2659 a train passing every internal gate is released with an alert and task, a failing copy is not`() {
        // the re-accreditation assessment is pending, an external rule changed and an open question is unanswered: none
        // of them is an input of the gates, which are the closed set below
        Gate.entries.map { it.name }.toSet() shouldBe
            setOf("SCENARIOS", "REGENERATION", "SECURITY", "CERTIFICATION", "CORPUS", "STAGING")
        evidence[TrainVersion("2026.10.0")] = evidenceOf()
        publishAs("rita", "2026.10.0") shouldBe null
        val train = trains.getValue(TrainVersion("2026.10.0"))
        train.accreditation shouldBe AccreditationCheck(true, "ALERT-2026.10.0", "TASK-2026.10.0")
        opened shouldBe listOf(TrainVersion("2026.10.0"))

        val changed = corpus + (DocumentId("D2") to "SR")
        evidence[TrainVersion("2026.10.1")] = evidenceOf(regeneration = RegenerationObservation(corpus, changed))
        val refusal = Refusal.parse(publishAs("rita", "2026.10.1")!!)
        refusal.code shouldBe Refusal.GATE_FAILED
        refusal.detail.startsWith("2026.10.1 REGENERATION ") shouldBe true
        refusal.detail.contains(";") shouldBe false // no other gate names a reason
        trains.containsKey(TrainVersion("2026.10.1")) shouldBe false
        opened.size shouldBe 1
    }

    @Test
    fun `INV-AC-2532 a fix to the released version is released while an external rule change waits`() {
        evidence[TrainVersion("2026.10.0")] = evidenceOf()
        publishAs("rita", "2026.10.0") shouldBe null
        evidence[TrainVersion("2026.10.1")] = evidenceOf()
        publishAs("rita", "2026.10.1", exposureReducing = true, kind = TrainKind.PATCH) shouldBe null
        trains.keys.map { it.value } shouldBe listOf("2026.10.0", "2026.10.1")
    }

    @Test
    fun `INV-AC-8578 the regeneration check passes when every document keeps its classification`() {
        evidence[TrainVersion("2026.10.0")] = evidenceOf(regeneration = RegenerationObservation(corpus, corpus.toMap()))
        publishAs("rita", "2026.10.0") shouldBe null
        val run =
            trains
                .getValue(TrainVersion("2026.10.0"))
                .record.runs
                .single { it.gate == Gate.REGENERATION }
        run.candidate shouldBe GateOutcome.PASSED
    }

    @Test
    fun `INV-AC-8578 the regeneration check fails the release on one changed document and names it`() {
        val after = corpus + (DocumentId("D2") to "SR")
        evidence[TrainVersion("2026.10.0")] = evidenceOf(regeneration = RegenerationObservation(corpus, after))
        val refusal = Refusal.parse(publishAs("rita", "2026.10.0")!!)
        refusal.code shouldBe Refusal.GATE_FAILED
        refusal.detail.contains("REGENERATION FAILED introduced=[D2]") shouldBe true
        trains shouldBe emptyMap()
    }

    @Test
    fun `INV-AC-8578 a lost or invented document is a difference and an empty corpus fails`() {
        val lost = RegenerationObservation(corpus, corpus - DocumentId("D1"))
        evidence[TrainVersion("2026.10.0")] = evidenceOf(regeneration = lost)
        Refusal.parse(publishAs("rita", "2026.10.0")!!).detail.contains("introduced=[D1]") shouldBe true
        val invented = RegenerationObservation(corpus, corpus + (DocumentId("D3") to "SR"))
        evidence[TrainVersion("2026.10.0")] = evidenceOf(regeneration = invented)
        Refusal.parse(publishAs("rita", "2026.10.0")!!).detail.contains("introduced=[D3]") shouldBe true
        evidence[TrainVersion("2026.10.0")] = evidenceOf(regeneration = RegenerationObservation(emptyMap(), emptyMap()))
        Refusal.parse(publishAs("rita", "2026.10.0")!!).code shouldBe Refusal.GATE_FAILED
    }

    @Test
    fun `INV-AC-2080 the regeneration check of the golden corpus fails the release on a difference (gate part)`() {
        val after = corpus + (DocumentId("D1") to "ZR")
        evidence[TrainVersion("2026.10.0")] = evidenceOf(regeneration = RegenerationObservation(corpus, after))
        Refusal.parse(publishAs("rita", "2026.10.0")!!).detail.contains("introduced=[D1]") shouldBe true
    }

    @Test
    fun `publishTrain repeated returns the original train and opens no second task`() {
        evidence[TrainVersion("2026.10.0")] = evidenceOf()
        publishAs("rita", "2026.10.0") shouldBe null
        publishAs("rita", "2026.10.0") shouldBe null
        opened.size shouldBe 1
    }

    @Test
    fun `setSupportWindow records the window and a repeat returns the original`() {
        val window = SupportWindow("2026.2", listOf(TrainVersion("2026.9.0"), TrainVersion("2026.10.0")))
        val command = SetSupportWindowCommand(ActorId("rita"), window, request)
        setWindow.permits("rita", command) shouldBe true
        setWindow.permits("alice", command.copy(actor = ActorId("alice"))) shouldBe false
        setWindow.unmetPrecondition(command, setWindow.load(command)) shouldBe null
        setWindow.perform(command, null) shouldBe window
        windows.getValue("2026.2") shouldBe window
        val repeat = command.copy(window = window.copy(trainVersions = listOf(TrainVersion("2026.11.0"))))
        setWindow.priorResult(repeat, setWindow.load(repeat)) shouldBe window
    }
}
