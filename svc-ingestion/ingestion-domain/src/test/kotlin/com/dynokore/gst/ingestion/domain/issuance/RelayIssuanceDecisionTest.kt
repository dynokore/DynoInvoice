package com.dynokore.gst.ingestion.domain.issuance

import com.dynokore.gst.ingestion.domain.issuance.IssuanceDecision.ApiUnavailable
import com.dynokore.gst.ingestion.domain.issuance.IssuanceDecision.HandOff
import com.dynokore.gst.ingestion.domain.issuance.IssuanceDecision.NotRecorded
import com.dynokore.gst.ingestion.domain.issuance.IssuanceDecision.Refused
import com.dynokore.gst.ingestion.domain.recording.RecordingOutcome
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldNotBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID

class RelayIssuanceDecisionTest {
    private val doc = UUID.fromString("00000000-0000-0000-0000-0000000000d1")
    private val warn = ValidationAnswer.Warn(listOf("REGISTER_UNAVAILABLE"))
    private val blocked = ValidationAnswer.Blocked(listOf("PAY-011"))

    private fun decide(
        recording: RecordingOutcome,
        validation: ValidationAnswer?,
    ) = RelayIssuanceDecision.decide(IssuanceFacts(recording, validation))

    private val unavailable = ApiUnavailable(UnavailableCause.VALIDATION_UNAVAILABLE)
    private val dependency = ApiUnavailable(UnavailableCause.DEPENDENCY_HOLD)

    @Test
    fun `INV-AC-13326 table of 3 recorded kinds by 5 validation answers`() {
        val kinds =
            mapOf(
                "New" to RecordingOutcome.New(doc),
                "Amend" to RecordingOutcome.Amend(doc, 2),
                "Replay" to RecordingOutcome.Replay(doc),
            )
        val answers: Map<String, ValidationAnswer?> =
            mapOf(
                "null" to null,
                "Unavailable" to ValidationAnswer.Unavailable,
                "Pass" to ValidationAnswer.Pass,
                "Warn" to warn,
                "Blocked" to blocked,
            )
        val expected: Map<Pair<String, String>, IssuanceDecision> =
            buildMap {
                for (kind in kinds.keys) {
                    put(kind to "null", unavailable)
                    put(kind to "Unavailable", unavailable)
                    put(kind to "Pass", HandOff(doc, emptyList()))
                    put(kind to "Warn", HandOff(doc, listOf("REGISTER_UNAVAILABLE")))
                    put(kind to "Blocked", Refused(doc, listOf("PAY-011"), RefusalSource.VALIDATION))
                }
            }
        expected.size shouldBe 15
        for ((kindName, kind) in kinds) {
            for ((answerName, answer) in answers) {
                val want = expected[kindName to answerName]
                (want != null) shouldBe true
                decide(kind, answer) shouldBe want
            }
        }
    }

    @Test
    fun `INV-AC-1619 New with Pass gives HandOff`() {
        decide(RecordingOutcome.New(doc), ValidationAnswer.Pass) shouldBe HandOff(doc, emptyList())
    }

    @Test
    fun `INV-AC-1620 New with Blocked PAY-004 gives Refused with that reason and never HandOff`() {
        val result = decide(RecordingOutcome.New(doc), ValidationAnswer.Blocked(listOf("PAY-004")))
        result shouldBe Refused(doc, listOf("PAY-004"), RefusalSource.VALIDATION)
        result.shouldNotBeInstanceOf<HandOff>()
    }

    @Test
    fun `INV-AC-525 New with Blocked PAY-011 gives Refused from VALIDATION`() {
        val result = decide(RecordingOutcome.New(doc), blocked) as Refused
        result.source shouldBe RefusalSource.VALIDATION
        result.reasons shouldContainExactly listOf("PAY-011")
    }

    @Test
    fun `INV-AC-2785 Amend with Pass after an earlier Refused keeps the Doc UUID`() {
        val first = decide(RecordingOutcome.New(doc), blocked) as Refused
        decide(RecordingOutcome.Amend(first.docUuid, 2), ValidationAnswer.Pass) shouldBe HandOff(doc, emptyList())
    }

    @Test
    fun `INV-AC-13326 Blocked with an empty list is still Refused and reasons keep order`() {
        decide(RecordingOutcome.New(doc), ValidationAnswer.Blocked(emptyList())) shouldBe
            Refused(doc, emptyList(), RefusalSource.VALIDATION)
        (decide(RecordingOutcome.New(doc), ValidationAnswer.Blocked(listOf("B", "A"))) as Refused)
            .reasons shouldContainExactly listOf("B", "A")
    }

    @Test
    fun `INV-AC-13326 Warn keeps its flags in order`() {
        decide(RecordingOutcome.New(doc), ValidationAnswer.Warn(listOf("Z", "A"))) shouldBe HandOff(doc, listOf("Z", "A"))
    }

    @Test
    fun `INV-AC-13326 holds other than ING-016 are Refused as DERIVATION_HOLD`() {
        for (reasons in listOf(listOf("MDM-001"), listOf("PER-011"), emptyList(), listOf("ING-0160"), listOf("ing-016"), listOf("ING-016 "))) {
            val result = decide(RecordingOutcome.Held(doc, 1, reasons), ValidationAnswer.Pass)
            result shouldBe Refused(doc, reasons, RefusalSource.DERIVATION_HOLD)
            result.shouldNotBeInstanceOf<HandOff>()
        }
    }

    @Test
    fun `INV-AC-13326 a hold with ING-016 is ApiUnavailable`() {
        decide(RecordingOutcome.Held(doc, 1, listOf("ING-016")), ValidationAnswer.Pass) shouldBe dependency
        decide(RecordingOutcome.Held(doc, 1, listOf("MDM-001", "ING-016")), ValidationAnswer.Pass) shouldBe dependency
    }

    @Test
    fun `INV-AC-13326 Stale Relayed with another code and Failed are NotRecorded`() {
        for (recording in listOf(RecordingOutcome.Stale, RecordingOutcome.Relayed(422, "ING-011"), RecordingOutcome.Failed)) {
            decide(recording, ValidationAnswer.Pass) shouldBe NotRecorded(recording)
        }
    }

    @Test
    fun `INV-AC-13326 Relayed with ING-016 is ApiUnavailable whatever the validation`() {
        val recording = RecordingOutcome.Relayed(422, "ING-016")
        for (answer in listOf(ValidationAnswer.Pass, warn, blocked, ValidationAnswer.Unavailable, null)) {
            val result = decide(recording, answer)
            result shouldBe dependency
            result.shouldNotBeInstanceOf<NotRecorded>()
        }
    }

    @Test
    fun `INV-AC-13326 look-alike ING-016 codes on Relayed are NotRecorded`() {
        for (code in listOf("ING-0160", "ing-016", "ING-016 ")) {
            val recording = RecordingOutcome.Relayed(422, code)
            decide(recording, ValidationAnswer.Pass) shouldBe NotRecorded(recording)
        }
    }

    @Test
    fun `INV-AC-13326 null facts give ApiUnavailable FACTS_MISSING`() {
        RelayIssuanceDecision.decide(null) shouldBe ApiUnavailable(UnavailableCause.FACTS_MISSING)
    }

    @Test
    fun `INV-AC-13326 the not-submittable reason constant`() {
        Refused.NOT_SUBMITTABLE_REASON shouldBe "ISSUANCE_REFUSED"
    }

    @Test
    fun `INV-AC-13326 every RecordingOutcome member is covered by a row`() {
        RecordingOutcome::class.sealedSubclasses.map { it.simpleName }.toSet() shouldBe
            setOf("New", "Replay", "Amend", "Stale", "Held", "Relayed", "Failed")
    }

    @Test
    fun `INV-AC-13326 structure pin decide has one IssuanceFacts parameter and facts has two fields`() {
        val method = RelayIssuanceDecision::class.java.declaredMethods.single { it.name == "decide" }
        method.parameterTypes.toList() shouldBe listOf(IssuanceFacts::class.java)
        IssuanceFacts::class.java.declaredFields.map { it.name }.toSet() shouldBe setOf("recording", "validation")
        method shouldNotBe null
    }
}
