package com.dynokore.gst.controlPlane.adapters

import com.dynokore.gst.controlPlane.adapters.persistence.GateRunRepositoryJooq
import com.dynokore.gst.controlPlane.application.pack.Refusal
import com.dynokore.gst.controlPlane.application.pack.RequestContext
import com.dynokore.gst.controlPlane.application.release.PublishTrain
import com.dynokore.gst.controlPlane.application.release.PublishTrainCommand
import com.dynokore.gst.controlPlane.application.release.RecordGateRun
import com.dynokore.gst.controlPlane.application.release.RecordGateRunCommand
import com.dynokore.gst.controlPlane.application.release.RecordedTrainEvidence
import com.dynokore.gst.controlPlane.application.release.ReleasePermission
import com.dynokore.gst.controlPlane.application.release.TrainRepository
import com.dynokore.gst.controlPlane.domain.pack.ActorId
import com.dynokore.gst.controlPlane.domain.release.Gate
import com.dynokore.gst.controlPlane.domain.release.GateOutcome
import com.dynokore.gst.controlPlane.domain.release.GateRunKey
import com.dynokore.gst.controlPlane.domain.release.GateRunRecord
import com.dynokore.gst.controlPlane.domain.release.GateRunSubmission
import com.dynokore.gst.controlPlane.domain.release.ReferenceEnvironment
import com.dynokore.gst.controlPlane.domain.release.ReleaseTrain
import com.dynokore.gst.controlPlane.domain.release.TrainKind
import com.dynokore.gst.controlPlane.domain.release.TrainVersion
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** The gate-run table and the production evidence binding over it, as the runtime login (INV-T-8749, CS-19). */
class GateRunPersistenceIT {
    private val clock = Clock.fixed(Instant.parse("2026-10-12T08:45:00Z"), ZoneOffset.UTC)
    private val dsl = PostgresDb.dsl()
    private val repository = GateRunRepositoryJooq(dsl)
    private val saas = ReferenceEnvironment.SAAS
    private val private = ReferenceEnvironment.PRIVATE_MINIMAL

    /** A train version unique to the test, so tests share one database without touching each other's rows. */
    private fun fresh() = TrainVersion("g" + UUID.randomUUID().toString().take(8))

    private fun record(
        train: TrainVersion,
        gate: Gate = Gate.SCENARIOS,
        environment: ReferenceEnvironment? = saas,
        candidate: GateOutcome = GateOutcome.PASSED,
    ) = GateRunRecord(
        train,
        gate,
        environment,
        candidate,
        null,
        emptySet(),
        emptySet(),
        "ev://run",
        ActorId("rita"),
        clock.instant(),
    )

    private fun records(train: TrainVersion) =
        listOf(
            record(train, Gate.SCENARIOS, saas),
            record(train, Gate.SCENARIOS, private),
            record(train, Gate.CERTIFICATION, saas),
            record(train, Gate.CERTIFICATION, private),
            record(train, Gate.SECURITY, null),
            record(train, Gate.REGENERATION, null),
        )

    private fun insertRaw(
        train: String,
        recordedBy: String?,
    ) = dsl.execute(
        "insert into controlplane.gate_run (id, train_version, gate, environment, candidate_result," +
            " candidate_findings, baseline_findings, recorded_by, recorded_at)" +
            " values (?, ?, 'SECURITY', null, 'PASSED', '{}', '{}', ?, now())",
        UUID.randomUUID(),
        train,
        recordedBy,
    )

    @Test
    fun `a record round-trips with the user ID, both findings sets and the optional fields`() {
        val train = fresh()
        val stored =
            record(train, Gate.SECURITY, null, GateOutcome.FAILED).copy(
                candidateFindings = setOf("F-9", "F-8"),
                baseline = GateOutcome.FAILED,
                baselineFindings = setOf("F-1"),
            )
        repository.insert(stored) shouldBe true
        repository.find(stored.key) shouldBe stored
        repository.find(GateRunKey(train, Gate.SECURITY, saas)).shouldBeNull()
        repository.insert(record(train, Gate.REGENERATION, null)) shouldBe true
        repository.forTrain(train).size shouldBe 2
    }

    @Test
    fun `UPDATE and DELETE as the runtime login are refused with a permission error`() {
        val train = fresh()
        repository.insert(record(train))
        shouldThrow<DataAccessException> {
            dsl.execute(
                "update controlplane.gate_run set candidate_result = 'FAILED' where train_version = ?",
                train.value,
            )
        }.message.shouldNotBeNull() shouldContain "permission denied"
        shouldThrow<DataAccessException> {
            dsl.execute("delete from controlplane.gate_run where train_version = ?", train.value)
        }.message.shouldNotBeNull() shouldContain "permission denied"
        repository.find(record(train).key).shouldNotBeNull()
    }

    @Test
    fun `a second insert with the same key is refused by the unique index`() {
        val train = fresh()
        repository.insert(record(train, Gate.SCENARIOS, saas)) shouldBe true
        repository.insert(record(train, Gate.SCENARIOS, saas, GateOutcome.FAILED)) shouldBe false
        repository.insert(record(train, Gate.SECURITY, null)) shouldBe true
        repository.insert(record(train, Gate.SECURITY, null, GateOutcome.FAILED)) shouldBe false
        shouldThrow<DataAccessException> { insertRaw(train.value, "rita") }.message.shouldNotBeNull() shouldContain
            "gate_run_key"
    }

    @Test
    fun `the other environment, and the same key for a longer train version, are accepted`() {
        val train = fresh()
        val longer = TrainVersion(train.value + "1")
        repository.insert(record(train, environment = saas)) shouldBe true
        repository.insert(record(train, environment = private)) shouldBe true
        repository.insert(record(longer, environment = saas)) shouldBe true
        repository.forTrain(train).size shouldBe 2
        repository.forTrain(longer).size shouldBe 1
    }

    @Test
    fun `an insert with recorded_by NULL or empty is refused by the table`() {
        listOf(null, "", "x".repeat(65)).forEach { by ->
            shouldThrow<DataAccessException> { insertRaw(fresh().value, by) }
        }
        insertRaw(fresh().value, "x".repeat(64)) shouldBe 1
    }

    @Test
    fun `the table refuses a gate, environment or result outside the contract's values`() {
        fun raw(
            gate: String,
            environment: String?,
            result: String,
        ) = dsl.execute(
            "insert into controlplane.gate_run (id, train_version, gate, environment, candidate_result," +
                " candidate_findings, baseline_findings, recorded_by, recorded_at)" +
                " values (?, ?, ?, ?, ?, '{}', '{}', 'rita', now())",
            UUID.randomUUID(),
            fresh().value,
            gate,
            environment,
            result,
        )
        listOf("CORPUS", "STAGING", "scenarios").forEach {
            shouldThrow<DataAccessException> { raw(it, null, "PASSED") }
        }
        shouldThrow<DataAccessException> { raw("SECURITY", "STAGING", "PASSED") }
        shouldThrow<DataAccessException> { raw("SECURITY", null, "passed") }
        raw("SECURITY", "SAAS", "PASSED") shouldBe 1
    }

    @Test
    fun `INV-AC-1943 the binding over the table holds a train back until the six records exist`() {
        val train = fresh()
        val source = RecordedTrainEvidence(repository)
        source.forTrain(train).shouldBeNull()
        records(train).forEach { repository.insert(it) }
        source.forTrain(train).shouldNotBeNull()
        // a look-alike: records of 'train + 1' never count for 'train'
        val other = TrainVersion(train.value + "1")
        source.forTrain(other).shouldBeNull()
        records(other).dropLast(1).forEach { repository.insert(it) }
        source.forTrain(other).shouldBeNull()
        source.forTrain(train)!!.certification.outcome shouldBe GateOutcome.PASSED
    }

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
            RecordedTrainEvidence(repository),
            clock,
        )

    private fun refusal(
        train: TrainVersion,
        exposureReducing: Boolean = false,
    ): Refusal? {
        val command =
            PublishTrainCommand(
                ActorId("rita"),
                train,
                TrainKind.TRAIN,
                exposureReducing,
                false,
                RequestContext("c", "k"),
            )
        return publish.unmetPrecondition(command, publish.load(command))?.let(Refusal::parse)
    }

    /** The six records of a train, with [change] applied to each. */
    private fun store(
        train: TrainVersion,
        change: (GateRunRecord) -> GateRunRecord? = { it },
    ) = records(train).mapNotNull(change).forEach { repository.insert(it) }

    private fun security(
        findings: Set<String>,
        baselineFindings: Set<String>,
    ): (GateRunRecord) -> GateRunRecord =
        {
            if (it.gate == Gate.SECURITY) {
                it.copy(
                    candidate = GateOutcome.FAILED,
                    candidateFindings = findings,
                    baseline = GateOutcome.FAILED,
                    baselineFindings = baselineFindings,
                )
            } else {
                it
            }
        }

    @Test
    fun `INV-AC-1943 with the six records of a certified train publishTrain publishes it`() {
        val train = fresh()
        store(train)
        refusal(train) shouldBe null
    }

    @Test
    fun `INV-AC-1943 with the PRIVATE_MINIMAL certification record absent or FAILED the train is refused`() {
        val absent = fresh()
        store(absent) { if (it.gate == Gate.CERTIFICATION && it.environment == private) null else it }
        refusal(absent)!!.code shouldBe Refusal.NO_GATE_EVIDENCE
        val failed = fresh()
        store(failed) {
            if (it.gate == Gate.CERTIFICATION &&
                it.environment == private
            ) {
                it.copy(candidate = GateOutcome.FAILED)
            } else {
                it
            }
        }
        refusal(failed)!!.code shouldBe Refusal.GATE_FAILED
    }

    @Test
    fun `INV-AC-2658 a new security finding blocks an exposure-reducing train and names it`() {
        val train = fresh()
        store(train, security(setOf("F-9"), setOf("F-1")))
        refusal(train, exposureReducing = true)!!.detail shouldContain "introduced=[F-9]"
    }

    @Test
    fun `INV-AC-2658 the same security finding as the released version does not block an exposure-reducing train`() {
        val train = fresh()
        store(train, security(setOf("F-9"), setOf("F-9")))
        refusal(train, exposureReducing = true) shouldBe null
        refusal(train, exposureReducing = false)!!.code shouldBe Refusal.GATE_FAILED
    }

    @Test
    fun `recordGateRun over the table stores the caller's user ID and a repeat stores nothing`() {
        val train = fresh()
        val operation =
            RecordGateRun(
                repository,
                object : TrainRepository {
                    override fun find(version: TrainVersion) = null

                    override fun save(train: ReleaseTrain) = Unit
                },
                { actor, key -> key == ReleasePermission.CERTIFY && actor in setOf("rita", "sam") },
                clock,
            )

        fun command(actor: String) =
            RecordGateRunCommand(
                ActorId(actor),
                train,
                GateRunSubmission(Gate.SECURITY, null, GateOutcome.PASSED),
                RequestContext("c", "k"),
            )
        val first = command("rita")
        operation.perform(first, operation.load(first)).recordedBy.value shouldBe "rita"
        val repeat = command("sam")
        val state = operation.load(repeat)
        operation.priorResult(repeat, state)!!.recordedBy.value shouldBe "rita"
        repository.forTrain(train).size shouldBe 1
    }
}
