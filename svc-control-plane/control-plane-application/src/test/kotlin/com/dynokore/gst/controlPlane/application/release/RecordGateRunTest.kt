package com.dynokore.gst.controlPlane.application.release

import com.dynokore.gst.controlPlane.application.pack.Refusal
import com.dynokore.gst.controlPlane.application.pack.RequestContext
import com.dynokore.gst.controlPlane.domain.pack.ActorId
import com.dynokore.gst.controlPlane.domain.release.AccreditationCheck
import com.dynokore.gst.controlPlane.domain.release.Gate
import com.dynokore.gst.controlPlane.domain.release.GateOutcome
import com.dynokore.gst.controlPlane.domain.release.GateRunKey
import com.dynokore.gst.controlPlane.domain.release.GateRunRecord
import com.dynokore.gst.controlPlane.domain.release.GateRunSubmission
import com.dynokore.gst.controlPlane.domain.release.ReferenceEnvironment
import com.dynokore.gst.controlPlane.domain.release.ReleaseRecord
import com.dynokore.gst.controlPlane.domain.release.ReleaseTrain
import com.dynokore.gst.controlPlane.domain.release.TrainKind
import com.dynokore.gst.controlPlane.domain.release.TrainVersion
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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

    private val scenarios = GateRunSubmission(Gate.SCENARIOS, ReferenceEnvironment.SAAS, GateOutcome.PASSED)

    private fun command(
        result: GateRunSubmission = scenarios,
        actor: String = "rita",
        train: TrainVersion = version,
    ) = RecordGateRunCommand(ActorId(actor), train, result, RequestContext("corr", "cause"))

    /** What the runner does: permit, load, repeat, precondition, perform. A refusal is returned as its code. */
    private fun run(command: RecordGateRunCommand): Any {
        check(operation.permits(command.actor.value, command)) { "not permitted" }
        val state = operation.load(command)
        val prior = operation.priorResult(command, state)
        val unmet = if (prior == null) operation.unmetPrecondition(command, state) else null
        return prior ?: unmet?.let(Refusal::parse) ?: operation.perform(command, state)
    }

    private fun refusalOf(command: RecordGateRunCommand) = (run(command) as Refusal).code

    @Test
    fun `a valid call stores one row with the caller's user ID and no role name`() {
        val stored = run(command()) as GateRunRecord
        stored.recordedBy.value shouldBe "rita"
        stored.recordedBy.value shouldNotBe "release.manager"
        runs.rows shouldBe listOf(stored)
        stored.recordedAt shouldBe clock.instant()
    }

    @Test
    fun `two callers that hold the permission record two gates and the rows hold the two user IDs`() {
        run(command(GateRunSubmission(Gate.SECURITY, null, GateOutcome.PASSED), actor = "rita"))
        run(command(GateRunSubmission(Gate.REGENERATION, null, GateOutcome.PASSED), actor = "sam"))
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
        val failed = scenarios.copy(candidate = GateOutcome.FAILED, candidateFindings = listOf("F-1"))
        val first = run(command(failed)) as GateRunRecord
        val again = run(command(failed, actor = "sam"))
        again shouldBe first
        runs.rows.size shouldBe 1
    }

    @Test
    fun `the same key with another result or other findings is a conflict`() {
        val failed = scenarios.copy(candidate = GateOutcome.FAILED, candidateFindings = listOf("F-1"))
        run(command(failed))
        refusalOf(command(scenarios)) shouldBe Refusal.GATE_RUN_DIFFERENT
        refusalOf(command(failed.copy(candidateFindings = listOf("F-2")))) shouldBe Refusal.GATE_RUN_DIFFERENT
        refusalOf(command(failed.copy(evidenceRef = "x"))) shouldBe Refusal.GATE_RUN_DIFFERENT
        runs.rows.size shouldBe 1
    }

    @Test
    fun `the other environment, and a train version that only shares a prefix, are other keys`() {
        run(command())
        run(command(scenarios.copy(environment = ReferenceEnvironment.PRIVATE_MINIMAL)))
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
        refusalOf(command()) shouldBe Refusal.GATE_RUN_TRAIN_PUBLISHED
        runs.rows shouldBe emptyList()
    }

    @Test
    fun `an environment is required for SCENARIOS and CERTIFICATION and refused for REGENERATION and SECURITY`() {
        listOf(Gate.SCENARIOS, Gate.CERTIFICATION).forEach {
            refusalOf(command(scenarios.copy(gate = it, environment = null))) shouldBe Refusal.GATE_RUN_INVALID
        }
        listOf(Gate.REGENERATION, Gate.SECURITY).forEach {
            refusalOf(command(scenarios.copy(gate = it))) shouldBe Refusal.GATE_RUN_INVALID
        }
        runs.rows shouldBe emptyList()
    }

    private fun fieldOf(command: RecordGateRunCommand) = (run(command) as Refusal).detail.substringBefore(' ')

    private val published =
        ReleaseTrain(
            version,
            TrainKind.TRAIN,
            false,
            ReleaseRecord(emptyList(), false, ActorId("rita"), clock.instant()),
            AccreditationCheck(false, null, null),
        )

    @Test
    fun `a baseline sent with a PASSED candidate is a rule refusal naming the field`() {
        refusalOf(command(scenarios.copy(baseline = GateOutcome.PASSED))) shouldBe Refusal.GATE_RUN_INVALID
        fieldOf(command(scenarios.copy(baseline = GateOutcome.PASSED))) shouldBe "/baselineResult"
        refusalOf(command(scenarios.copy(baselineFindings = listOf("F-1")))) shouldBe Refusal.GATE_RUN_INVALID
        fieldOf(command(scenarios.copy(baselineFindings = listOf("F-1")))) shouldBe "/baselineFindings"
        runs.rows shouldBe emptyList()
    }

    @Test
    fun `a gate the service produces itself is a rule refusal on the field gate`() {
        listOf(Gate.CORPUS, Gate.STAGING).forEach {
            refusalOf(command(scenarios.copy(gate = it, environment = null))) shouldBe Refusal.GATE_RUN_INVALID
            fieldOf(command(scenarios.copy(gate = it))) shouldBe "/gate"
        }
        fieldOf(command(scenarios.copy(environment = null))) shouldBe "/environment"
        runs.rows shouldBe emptyList()
    }

    @Test
    fun `a record that breaks a gate rule for a published train is the rule refusal`() {
        trains[version] = published
        refusalOf(command(scenarios.copy(environment = null))) shouldBe Refusal.GATE_RUN_INVALID
    }

    @Test
    fun `a different record for the key of a published train is the published refusal`() {
        val failed = scenarios.copy(candidate = GateOutcome.FAILED, candidateFindings = listOf("F-1"))
        run(command(failed))
        trains[version] = published
        refusalOf(command(scenarios)) shouldBe Refusal.GATE_RUN_TRAIN_PUBLISHED
    }

    @Test
    fun `a size-rule violation is no CPL refusal`() {
        shouldThrow<IllegalArgumentException> { scenarios.copy(candidateFindings = List(501) { "F-$it" }) }
        shouldThrow<IllegalArgumentException> { scenarios.copy(baselineFindings = List(501) { "F-$it" }) }
        shouldThrow<IllegalArgumentException> { scenarios.copy(candidateFindings = listOf(" ")) }
        shouldThrow<IllegalArgumentException> { scenarios.copy(candidateFindings = listOf("x".repeat(257))) }
        shouldThrow<IllegalArgumentException> { scenarios.copy(evidenceRef = " ") }
        shouldThrow<IllegalArgumentException> { scenarios.copy(evidenceRef = "x".repeat(257)) }
        val atLimit = scenarios.copy(candidateFindings = List(500) { "F-$it" }, evidenceRef = "x".repeat(256))
        atLimit.violation() shouldBe null
        runs.rows shouldBe emptyList()
    }
}
