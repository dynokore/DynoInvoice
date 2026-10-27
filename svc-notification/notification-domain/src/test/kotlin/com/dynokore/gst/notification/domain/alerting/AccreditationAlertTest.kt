package com.dynokore.gst.notification.domain.alerting

import com.dynokore.gst.notification.domain.alerting.AccreditationDecision.Raise
import com.dynokore.gst.notification.domain.alerting.AccreditationDecision.Withhold
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class AccreditationAlertTest {
    private fun event(
        reason: AccreditationReason = AccreditationReason.ACCREDITATION_LAPSED,
        alertRef: String = "acc-alert-0001",
        taskRef: String = "acc-task-0001",
        releaseRef: String? = null,
    ) = AccreditationAlertRaised(alertRef, taskRef, reason, Instant.parse("2026-10-19T00:00:00Z"), releaseRef, null)

    private val two = listOf("release.manager:jane.tan", "release.manager:ravi.k")

    @Test
    fun `INV-AC-2340 lapse event raises one ACCREDITATION alert to both holders with no tier`() {
        val d = AccreditationAlert.decide(event(), two, emptySet())
        d shouldBe
            Raise(
                AccreditationAlertDraft(
                    alertClass = "ACCREDITATION",
                    trigger = "ACCREDITATION_LAPSED",
                    entityRef = "ffffffff-ffff-ffff-ffff-ffffffffffff",
                    alertRef = "acc-alert-0001",
                    taskRef = "acc-task-0001",
                    recipients = two,
                    tiers = emptyList(),
                    channel = AccreditationChannel.EMAIL,
                ),
            )
    }

    @Test
    fun `alerted ref is withheld and another ref raises`() {
        AccreditationAlert.decide(event(), two, setOf("acc-alert-0001")) shouldBe
            Withhold(AccreditationWithheld.ALREADY_ALERTED)
        (AccreditationAlert.decide(event(), two, setOf("acc-alert-0002")) is Raise) shouldBe true
    }

    @Test
    fun `no usable recipient is withheld`() {
        AccreditationAlert.decide(event(), emptyList(), emptySet()) shouldBe
            Withhold(AccreditationWithheld.NO_RECIPIENT)
        AccreditationAlert.decide(event(), listOf("  "), emptySet()) shouldBe
            Withhold(AccreditationWithheld.NO_RECIPIENT)
    }

    @Test
    fun `alerted ref wins over no recipient`() {
        AccreditationAlert.decide(event(), emptyList(), setOf("acc-alert-0001")) shouldBe
            Withhold(AccreditationWithheld.ALREADY_ALERTED)
    }

    @Test
    fun `recipients are distinct case-sensitive and ordered`() {
        val d = AccreditationAlert.decide(event(), listOf("a", "a", "A"), emptySet()) as Raise
        d.draft.recipients shouldBe listOf("a", "A")
    }

    @Test
    fun `reassessment reason gives its own trigger`() {
        val d = AccreditationAlert.decide(event(AccreditationReason.REASSESSMENT_NEEDED), two, emptySet()) as Raise
        d.draft.trigger shouldBe "REASSESSMENT_NEEDED"
    }

    @Test
    fun `equal input gives equal output`() {
        AccreditationAlert.decide(event(), two, emptySet()) shouldBe AccreditationAlert.decide(event(), two, emptySet())
    }

    @Test
    fun `constructor refuses blank and over-long refs`() {
        shouldThrow<IllegalArgumentException> { event(alertRef = " ") }
        shouldThrow<IllegalArgumentException> { event(taskRef = "t".repeat(129)) }
        shouldThrow<IllegalArgumentException> { event(releaseRef = "") }
        event(taskRef = "t".repeat(128), releaseRef = "r".repeat(128)).taskRef.length shouldBe 128
    }

    @Test
    fun `fromKey is exact`() {
        listOf("accreditation_lapsed", "ACCREDITATION_LAPSED ", "ACCREDITATION_LAPSE", "").forEach {
            shouldThrow<IllegalArgumentException> { AccreditationReason.fromKey(it) }
        }
        AccreditationReason.fromKey("ACCREDITATION_LAPSED") shouldBe AccreditationReason.ACCREDITATION_LAPSED
        AccreditationReason.fromKey("REASSESSMENT_NEEDED") shouldBe AccreditationReason.REASSESSMENT_NEEDED
    }

    @Test
    fun `enum member sets and constants are fixed`() {
        AccreditationReason.entries.map { it.name }.toSet() shouldBe
            setOf("ACCREDITATION_LAPSED", "REASSESSMENT_NEEDED")
        AccreditationWithheld.entries.map { it.name }.toSet() shouldBe setOf("ALREADY_ALERTED", "NO_RECIPIENT")
        ROLE_RELEASE_MANAGER shouldBe "release.manager"
    }

    @Test
    fun `message holds exactly five properties and no invoice content`() {
        AccreditationMessageParameters::class.java.declaredFields
            .filter {
                !java.lang.reflect.Modifier
                    .isStatic(it.modifiers)
            }.map { it.name }
            .toSet() shouldBe
            setOf("template", "reason", "taskRef", "releaseRef", "currentUntil")
        val m = AccreditationMessage.of(event(releaseRef = "rel-1").copy(currentUntil = LocalDate.of(2026, 12, 31)))
        m shouldBe
            AccreditationMessageParameters(
                "ACCREDITATION_ALERT",
                AccreditationReason.ACCREDITATION_LAPSED,
                "acc-task-0001",
                "rel-1",
                LocalDate.of(2026, 12, 31),
            )
    }
}
