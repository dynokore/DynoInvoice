package com.dynokore.gst.controlPlane.application.release

import com.dynokore.gst.controlPlane.application.pack.Refusal
import com.dynokore.gst.controlPlane.application.pack.RequestContext
import com.dynokore.gst.controlPlane.domain.pack.ActorId
import com.dynokore.gst.controlPlane.domain.release.AccreditationCheck
import com.dynokore.gst.controlPlane.domain.release.Gate
import com.dynokore.gst.controlPlane.domain.release.GateOutcome
import com.dynokore.gst.controlPlane.domain.release.GateRunKey
import com.dynokore.gst.controlPlane.domain.release.GateRunRecord
import com.dynokore.gst.controlPlane.domain.release.ReferenceEnvironment
import com.dynokore.gst.controlPlane.domain.release.ReleaseRecord
import com.dynokore.gst.controlPlane.domain.release.ReleaseTrain
import com.dynokore.gst.controlPlane.domain.release.TrainKind
import com.dynokore.gst.controlPlane.domain.release.TrainVersion
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** An in-memory store of gate runs; the train version is matched whole. */
class MemoryGateRuns : GateRunRepository {
    val rows = ArrayList<GateRunRecord>()

    override fun find(key: GateRunKey) = rows.singleOrNull { it.key == key }

    override fun insert(record: GateRunRecord): Boolean = if (find(record.key) != null) false else rows.add(record)

    override fun forTrain(version: TrainVersion) = rows.filter { it.trainVersion == version }
}

/** recordGateRun at operation level (INV-REQ-2657): permission by key, user ID recorded, repeat and conflict rules. */
class RecordGateRunTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-12T08:45:00Z"), ZoneOffset.UTC)
    private val runs = MemoryGateRuns()
    private val trains = HashMap<TrainVersion, ReleaseTrain>()
    private val trainRepository =
        object : TrainRepository {
            override fun find(version: TrainVersion) = trains[version]

            override fun save(train: ReleaseTrain) {
                trains[train.version] = train
            }
        }
    private val holders = setOf("rita", "sam")
    private val operation =
        RecordGateRun(
            runs,
            trainRepository,
            { actor, key -> key == ReleasePermission.CERTIFY && actor in holders },
            clock,
        )
    private val version = TrainVersion("2026.10.0")

    private fun command(
        actor: String = "rita",
        train: TrainVersion = version,
        gate: Gate = Gate.SCENARIOS,
        environment: ReferenceEnvironment? = ReferenceEnvironment.SAAS,
        candidate: GateOutcome = GateOutcome.PASSED,
        baseline: GateOutcome? = null,
        candidateFindings: List<String> = emptyList(),
        baselineFindings: List<String> = emptyList(),
        evidenceRef: String? = null,
    ) = RecordGateRunCommand(
        ActorId(actor),
        train,
        gate,
        environment,
        candidate,
        baseline,
        candidateFindings,
        baselineFindings,
        evidenceRef,
        RequestContext("corr", "cause"),
    )

    /** What the runner does: permit, load, repeat, precondition, perform. A refusal is returned as its code. */
    private fun run(command: RecordGateRunCommand): Any {
        check(operation.permits(command.actor.value, command)) { "not permitted" }
        val state = operation.load(command)
        operation.priorResult(command, state)?.let { return it }
        operation.unmetPrecondition(command, state)?.let { return Refusal.parse(it) }
        return operation.perform(command, state)
    }

    private fun refusalOf(command: RecordGateRunCommand) = (run(command) as Refusal).code

    @Test
    fun `a valid call stores one row with the caller's user ID and no role name`() {
        val stored = run(command()) as GateRunRecord
        stored.recordedBy.value shouldBe "rita"
        stored.recordedBy.value shouldBe stored.recordedBy.value.takeIf { it != "release.manager" }
        runs.rows shouldBe listOf(stored)
        stored.recordedAt shouldBe clock.instant()
    }

    @Test
    fun `two callers that hold the permission record two gates and the rows hold the two user IDs`() {
        run(command(actor = "rita", gate = Gate.SECURITY, environment = null))
        run(command(actor = "sam", gate = Gate.REGENERATION, environment = null))
        runs.rows.map { it.recordedBy.value } shouldBe listOf("rita", "sam")
    }

    @Test
    fun `the permission is the certify key alone and the actor is the caller`() {
        val asked = ArrayList<String>()

        fun with(allowed: String): RecordGateRun =
            RecordGateRun(runs, trainRepository, { actor, key ->
                asked += key
                actor == "rita" && key == allowed
            }, clock)
        with(ReleasePermission.CERTIFY).permits("rita", command()) shouldBe true
        asked.toSet() shouldBe setOf(ReleasePermission.CERTIFY)
        listOf(ReleasePermission.PUBLISH, "release-train.certify ", "Release-train.certify").forEach {
            with(it).permits("rita", command()) shouldBe false
        }
        with(ReleasePermission.CERTIFY).permits("rita", command(actor = "sam")) shouldBe false
        with(ReleasePermission.CERTIFY).permits("sam", command(actor = "sam")) shouldBe false
        operation.permits("bob", command(actor = "bob")) shouldBe false
    }

    @Test
    fun `a repeat of the same result returns the stored record and stores nothing`() {
        val first = run(command(candidate = GateOutcome.FAILED, candidateFindings = listOf("F-1"))) as GateRunRecord
        val again = run(command(actor = "sam", candidate = GateOutcome.FAILED, candidateFindings = listOf("F-1")))
        again shouldBe first
        runs.rows.size shouldBe 1
    }

    @Test
    fun `the same key with another result or other findings is a conflict`() {
        run(command(candidate = GateOutcome.FAILED, candidateFindings = listOf("F-1")))
        refusalOf(command(candidate = GateOutcome.PASSED)) shouldBe Refusal.GATE_RUN_CONFLICT
        refusalOf(command(candidate = GateOutcome.FAILED, candidateFindings = listOf("F-2"))) shouldBe
            Refusal.GATE_RUN_CONFLICT
        refusalOf(
            command(candidate = GateOutcome.FAILED, candidateFindings = listOf("F-1"), evidenceRef = "x"),
        ) shouldBe
            Refusal.GATE_RUN_CONFLICT
        runs.rows.size shouldBe 1
    }

    @Test
    fun `the other environment, and a train version that only shares a prefix, are other keys`() {
        run(command())
        run(command(environment = ReferenceEnvironment.PRIVATE_MINIMAL))
        run(command(train = TrainVersion("2026.10.01")))
        runs.rows.size shouldBe 3
    }

    @Test
    fun `a record for a train that is already published is a conflict`() {
        trains[version] =
            ReleaseTrain(
                version,
                TrainKind.TRAIN,
                false,
                ReleaseRecord(emptyList(), false, ActorId("rita"), clock.instant()),
                AccreditationCheck(false, null, null),
            )
        refusalOf(command()) shouldBe Refusal.GATE_RUN_CONFLICT
        runs.rows shouldBe emptyList()
    }

    @Test
    fun `an environment is required for SCENARIOS and CERTIFICATION and refused for REGENERATION and SECURITY`() {
        listOf(Gate.SCENARIOS, Gate.CERTIFICATION).forEach {
            refusalOf(command(gate = it, environment = null)) shouldBe Refusal.GATE_RUN_INVALID
        }
        listOf(Gate.REGENERATION, Gate.SECURITY).forEach {
            refusalOf(command(gate = it, environment = ReferenceEnvironment.SAAS)) shouldBe Refusal.GATE_RUN_INVALID
        }
        runs.rows shouldBe emptyList()
    }

    @Test
    fun `a baseline sent with a PASSED candidate, or a gate the service produces itself, is refused`() {
        refusalOf(command(baseline = GateOutcome.PASSED)) shouldBe Refusal.GATE_RUN_INVALID
        refusalOf(command(baselineFindings = listOf("F-1"))) shouldBe Refusal.GATE_RUN_INVALID
        listOf(Gate.CORPUS, Gate.STAGING).forEach {
            refusalOf(command(gate = it, environment = null)) shouldBe Refusal.GATE_RUN_INVALID
            refusalOf(command(gate = it)) shouldBe Refusal.GATE_RUN_INVALID
        }
        refusalOf(command(candidateFindings = List(501) { "F-$it" })) shouldBe Refusal.GATE_RUN_INVALID
        refusalOf(command(evidenceRef = "x".repeat(257))) shouldBe Refusal.GATE_RUN_INVALID
        runs.rows shouldBe emptyList()
    }
}
