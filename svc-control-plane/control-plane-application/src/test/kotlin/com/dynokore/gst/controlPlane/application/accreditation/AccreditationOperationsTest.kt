package com.dynokore.gst.controlPlane.application.accreditation

import com.dynokore.gst.controlPlane.application.pack.RequestContext
import com.dynokore.gst.controlPlane.application.release.OperatorPermissions
import com.dynokore.gst.controlPlane.application.release.PublishTrain
import com.dynokore.gst.controlPlane.application.release.PublishTrainCommand
import com.dynokore.gst.controlPlane.application.release.ReleasePermission
import com.dynokore.gst.controlPlane.application.release.TrainRepository
import com.dynokore.gst.controlPlane.domain.accreditation.AccreditationRecord
import com.dynokore.gst.controlPlane.domain.accreditation.Currency
import com.dynokore.gst.controlPlane.domain.accreditation.SandboxAcknowledgement
import com.dynokore.gst.controlPlane.domain.accreditation.SubmissionType
import com.dynokore.gst.controlPlane.domain.pack.ActorId
import com.dynokore.gst.controlPlane.domain.release.Certification
import com.dynokore.gst.controlPlane.domain.release.DocumentId
import com.dynokore.gst.controlPlane.domain.release.GateOutcome
import com.dynokore.gst.controlPlane.domain.release.Observation
import com.dynokore.gst.controlPlane.domain.release.ReferenceEnvironment
import com.dynokore.gst.controlPlane.domain.release.RegenerationObservation
import com.dynokore.gst.controlPlane.domain.release.ReleaseTrain
import com.dynokore.gst.controlPlane.domain.release.TrainEvidence
import com.dynokore.gst.controlPlane.domain.release.TrainKind
import com.dynokore.gst.controlPlane.domain.release.TrainVersion
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class AccreditationOperationsTest {
    private val now = Instant.parse("2026-10-12T08:45:00Z")
    private val today = LocalDate.parse("2026-10-12")
    private var clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    private val request = RequestContext("corr-1", "cause-1")
    private val rows = ArrayList<AccreditationRecord>()
    private val asked = ArrayList<Pair<String, String>>()

    /** The stub: [holders] hold exactly RECORD; [viewers] hold exactly READ. */
    private var holders = setOf("alice", "bob")
    private var viewers = setOf("alice", "bob")
    private val permissions =
        OperatorPermissions { actor, key ->
            asked += actor to key
            (key == AccreditationPermission.RECORD && actor in holders) ||
                (key == AccreditationPermission.READ && actor in viewers)
        }
    private val records =
        object : AccreditationRecords {
            // the latest recorded_at wins; the list order is deliberately not the answer
            override fun latest() = rows.maxByOrNull { it.recordedAt }

            override fun find(
                imdaReference: String,
                currentUntil: LocalDate,
            ) = rows.firstOrNull { it.imdaReference == imdaReference && it.currentUntil == currentUntil }

            override fun save(record: AccreditationRecord) {
                rows += record
            }
        }
    private val record get() = RecordAccreditation(records, permissions, clock)
    private val read get() = GetAccreditation(records, permissions, clock)

    @Suppress("LongParameterList") // a builder with a default for every field
    private fun commandOf(
        actor: String,
        currentUntil: LocalDate = today.plusDays(300),
        reference: String = "IMDA-1",
        types: List<SubmissionType> = SubmissionType.entries,
        acknowledgements: List<SandboxAcknowledgement> = emptyList(),
        grantedOn: LocalDate = today.minusDays(65),
    ) = RecordAccreditationCommand(ActorId(actor), grantedOn, currentUntil, reference, types, acknowledgements, request)

    /** The runner's sequence for one call: permit, repeat, precondition, perform. Null when not permitted. */
    private fun call(command: RecordAccreditationCommand): AccreditationRecord? {
        val op = record
        if (!op.permits(command.actor.value, command)) return null
        val state = op.load(command)
        check(state != null || op.unmetPrecondition(command, state) == null)
        return op.priorResult(command, state) ?: op.perform(command, state)
    }

    private fun lapsed() = commandOf("alice", currentUntil = today.minusDays(1))

    // --- publishTrain beside a lapsed record
    private val trains = LinkedHashMap<TrainVersion, ReleaseTrain>()
    private val publishPermissions =
        OperatorPermissions { actor, key -> actor == "rita" && key == ReleasePermission.PUBLISH }
    private val corpus = mapOf(DocumentId("D1") to "SR")
    private val certified =
        TrainEvidence(
            Observation(GateOutcome.PASSED),
            Observation(GateOutcome.PASSED),
            RegenerationObservation(corpus, corpus),
            Certification(ReferenceEnvironment.entries.associateWith { GateOutcome.PASSED }),
            null,
        )
    private val publish =
        PublishTrain(
            object : TrainRepository {
                override fun find(version: TrainVersion) = trains[version]

                override fun save(train: ReleaseTrain) {
                    trains[train.version] = train
                }
            },
            publishPermissions,
            { certified },
            clock,
        )

    @Test
    fun `INV-AC-2338 a lapsed record is reported LAPSED, readable, and no release is blocked by the lapse`() {
        call(lapsed()) shouldBe rows.single()
        val status = read.get("alice")!!
        status.currency shouldBe Currency.LAPSED
        status.record shouldBe rows.single()
        val command =
            PublishTrainCommand(ActorId("rita"), TrainVersion("2026.10.0"), TrainKind.TRAIN, false, false, request)
        publish.permits("rita", command) shouldBe true
        val state = publish.load(command)
        publish.unmetPrecondition(command, state) shouldBe null // the lapse refuses nothing
        publish.perform(command, state).version shouldBe TrainVersion("2026.10.0")
        trains.keys shouldBe setOf(TrainVersion("2026.10.0"))
        read.get("alice")!!.currency shouldBe Currency.LAPSED
    }

    @Test
    fun `INV-AC-2339 a record with an acknowledgement for each of the four types returns all four with their ids`() {
        val acks = SubmissionType.entries.map { SandboxAcknowledgement(it, "ACK-${it.name}") }
        call(commandOf("alice", acknowledgements = acks))
        val shown = read.get("alice")!!.record!!
        shown.sandboxAcknowledgements.map { it.submissionType } shouldBe
            listOf(SubmissionType.TYPE_1A, SubmissionType.TYPE_1B, SubmissionType.TYPE_2, SubmissionType.TYPE_3)
        shown.sandboxAcknowledgements.map { it.acknowledgementId } shouldBe
            listOf("ACK-TYPE_1A", "ACK-TYPE_1B", "ACK-TYPE_2", "ACK-TYPE_3")
        shown.typesWithoutAcknowledgement shouldBe emptyList()
    }

    @Test
    fun `a covered type without an acknowledgement is accepted and shown as having none`() {
        val acks = listOf(SandboxAcknowledgement(SubmissionType.TYPE_1A, "ACK-1"))
        call(commandOf("alice", acknowledgements = acks)) shouldBe rows.single()
        read.get("alice")!!.record!!.typesWithoutAcknowledgement shouldBe
            listOf(SubmissionType.TYPE_1B, SubmissionType.TYPE_2, SubmissionType.TYPE_3)
    }

    @Test
    fun `the record carries the actor string passed to holds and never the permission holder role`() {
        val result = call(commandOf("alice"))!!
        result.recordedBy shouldBe ActorId("alice")
        asked.first() shouldBe ("alice" to AccreditationPermission.RECORD)
        rows.single().recordedBy.value shouldBe asked.first().first
    }

    @Test
    fun `two actors that hold the permission each record once and the rows hold their own actor strings`() {
        call(commandOf("alice", reference = "IMDA-1"))
        call(commandOf("bob", reference = "IMDA-2"))
        rows.map { it.recordedBy.value } shouldBe listOf("alice", "bob")
        rows.none { it.recordedBy.value == "release.manager" } shouldBe true
    }

    @Test
    fun `a caller without the key or naming another actor is not permitted and stores nothing`() {
        call(commandOf("carol")) shouldBe null // carol is no holder: 403
        record.permits("alice", commandOf("bob")) shouldBe false
        holders = setOf("alice")
        listOf("accreditation.records", "Accreditation.record").forEach { key ->
            OperatorPermissions { _, asked -> asked == key }.let {
                RecordAccreditation(records, it, clock).permits("alice", commandOf("alice")) shouldBe false
            }
        }
        rows shouldBe emptyList()
        read.get("carol") shouldBe null
    }

    @Test
    fun `INV-AC-2338 getAccreditation asks exactly actor and accreditation view and the keys are distinct`() {
        AccreditationPermission.READ shouldBe "accreditation.view"
        AccreditationPermission.RECORD shouldBe "accreditation.record"
        asked.clear()
        read.get("alice")
        asked shouldBe listOf("alice" to "accreditation.view")
    }

    @Test
    fun `INV-AC-2338 a holder of only accreditation record is refused the read`() {
        viewers = emptySet()
        holders = setOf("alice")
        call(commandOf("alice"))!!
        read.get("alice") shouldBe null
    }

    @Test
    fun `INV-AC-2338 a holder of only accreditation view reads and is refused to record`() {
        viewers = setOf("vera")
        holders = emptySet()
        read.get("vera")!!.currency shouldBe Currency.NOT_RECORDED
        record.permits("vera", commandOf("vera")) shouldBe false
        rows shouldBe emptyList()
    }

    @Test
    fun `a repeat of reference and currentUntil returns the existing record and stores nothing`() {
        val first = call(commandOf("alice"))!!
        clock = Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC)
        call(commandOf("bob", types = listOf(SubmissionType.TYPE_1A))) shouldBe first
        rows.size shouldBe 1
    }

    @Test
    fun `currentUntil before grantedOn is refused as a contract violation`() {
        shouldThrow<IllegalArgumentException> { commandOf("alice", currentUntil = today.minusDays(100)) }
        // equal is fine
        commandOf("alice", currentUntil = today.minusDays(65)).currentUntil shouldBe today.minusDays(65)
        rows shouldBe emptyList()
    }

    @Test
    fun `currentUntil equal to today is CURRENT and the day before is LAPSED`() {
        call(commandOf("alice", currentUntil = today))
        read.get("alice")!!.currency shouldBe Currency.CURRENT
        rows.clear()
        call(commandOf("alice", currentUntil = today.minusDays(1)))
        read.get("alice")!!.currency shouldBe Currency.LAPSED
        rows.clear()
        call(commandOf("alice", currentUntil = today.plusDays(1)))
        read.get("alice")!!.currency shouldBe Currency.CURRENT
    }

    @Test
    fun `the date of the read is the UTC date of the clock`() {
        call(commandOf("alice", currentUntil = today))
        // 23:30 UTC on the 12th is already the 13th in Singapore; the UTC date decides
        clock = Clock.fixed(Instant.parse("2026-10-12T23:30:00Z"), ZoneOffset.ofHours(8))
        read.get("alice")!!.let {
            it.asOf shouldBe today
            it.currency shouldBe Currency.CURRENT
        }
        clock = Clock.fixed(Instant.parse("2026-10-13T00:00:00Z"), ZoneOffset.ofHours(-8))
        read.get("alice")!!.currency shouldBe Currency.LAPSED
    }

    @Test
    fun `the empty store gives NOT_RECORDED without a record`() {
        read.get("alice")!!.let {
            it.currency shouldBe Currency.NOT_RECORDED
            it.record shouldBe null
            it.asOf shouldBe today
        }
    }

    @Test
    fun `a second record supersedes the first in the status and the first stays stored`() {
        call(commandOf("alice", reference = "IMDA-1", currentUntil = today.minusDays(1)))
        clock = Clock.fixed(now.plusSeconds(3600), ZoneOffset.UTC)
        val second = call(commandOf("bob", reference = "IMDA-2", currentUntil = today.plusDays(365)))!!
        rows.size shouldBe 2
        read.get("alice")!!.let {
            it.currency shouldBe Currency.CURRENT
            it.record shouldBe second
        }
        rows.first().imdaReference shouldBe "IMDA-1"
    }
}
