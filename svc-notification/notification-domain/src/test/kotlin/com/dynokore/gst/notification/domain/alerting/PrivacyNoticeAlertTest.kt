package com.dynokore.gst.notification.domain.alerting

import com.dynokore.gst.notification.domain.alerting.PrivacyNoticeDecision.Raise
import com.dynokore.gst.notification.domain.alerting.PrivacyNoticeDecision.Withhold
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

class PrivacyNoticeAlertTest {
    private val caseId = "7c9e6679-7425-40de-944b-e07fc1f90ae7"

    private fun notice(
        id: String = caseId,
        received: LocalDate = LocalDate.of(2026, 3, 1),
        deadline: LocalDate = LocalDate.of(2026, 3, 31),
        lead: Int = 7,
    ) = PrivacyNoticeDue(id, PrivacyRequestKind.ACCESS, received, deadline, lead)

    private val two = listOf("security.officer:jane.tan", "security.officer:ravi.k")

    @Test
    fun `INV-AC-1404 due notice raises one SECURITY alert to both officers with no tier and carries the deadline`() {
        PrivacyNoticeAlert.decide(notice(), two, emptySet()) shouldBe
            Raise(
                PrivacyNoticeAlertDraft(
                    alertClass = "SECURITY",
                    trigger = "PRIVACY_NOTICE_DUE",
                    entityRef = "00000000-0000-0000-0000-000000000000",
                    caseId = caseId,
                    recipients = two,
                    tiers = emptyList(),
                ),
            )
        PrivacyNoticeMessage.of(notice()).responseDeadline shouldBe LocalDate.of(2026, 3, 31)
    }

    @Test
    fun `alerted case is withheld and another case raises`() {
        PrivacyNoticeAlert.decide(notice(), two, setOf(caseId)) shouldBe Withhold(PrivacyNoticeWithheld.ALREADY_ALERTED)
        (PrivacyNoticeAlert.decide(notice(), two, setOf("other")) is Raise) shouldBe true
    }

    @Test
    fun `no usable recipient is withheld`() {
        PrivacyNoticeAlert.decide(notice(), emptyList(), emptySet()) shouldBe Withhold(PrivacyNoticeWithheld.NO_RECIPIENT)
        PrivacyNoticeAlert.decide(notice(), listOf("  "), emptySet()) shouldBe Withhold(PrivacyNoticeWithheld.NO_RECIPIENT)
    }

    @Test
    fun `alerted case wins over no recipient`() {
        PrivacyNoticeAlert.decide(notice(), emptyList(), setOf(caseId)) shouldBe
            Withhold(PrivacyNoticeWithheld.ALREADY_ALERTED)
    }

    @Test
    fun `recipients are distinct case-sensitive and ordered`() {
        val d = PrivacyNoticeAlert.decide(notice(), listOf("a", "a", "A"), emptySet()) as Raise
        d.draft.recipients shouldBe listOf("a", "A")
    }

    @Test
    fun `equal input gives equal output`() {
        PrivacyNoticeAlert.decide(notice(), two, emptySet()) shouldBe PrivacyNoticeAlert.decide(notice(), two, emptySet())
    }

    @Test
    fun `constructor refuses bad case id, lead and deadline`() {
        notice(deadline = LocalDate.of(2026, 3, 1)).responseDeadline shouldBe LocalDate.of(2026, 3, 1)
        shouldThrow<IllegalArgumentException> { notice(deadline = LocalDate.of(2026, 2, 28)) }
        shouldThrow<IllegalArgumentException> { notice(id = "CASE-1") }
        shouldThrow<IllegalArgumentException> { notice(id = caseId.uppercase()) }
        shouldThrow<IllegalArgumentException> { notice(lead = 0) }
    }

    @Test
    fun `fromKey is exact`() {
        listOf("access", "ACCESS ", "ACCESS_REQUEST", "").forEach {
            shouldThrow<IllegalArgumentException> { PrivacyRequestKind.fromKey(it) }
        }
        PrivacyRequestKind.fromKey("ACCESS") shouldBe PrivacyRequestKind.ACCESS
        PrivacyRequestKind.fromKey("CORRECTION") shouldBe PrivacyRequestKind.CORRECTION
    }

    @Test
    fun `enum member sets and constants are fixed`() {
        PrivacyRequestKind.entries.map { it.name }.toSet() shouldBe setOf("ACCESS", "CORRECTION")
        PrivacyNoticeWithheld.entries.map { it.name }.toSet() shouldBe setOf("ALREADY_ALERTED", "NO_RECIPIENT")
        PRIVACY_NOTICE_TEMPLATE shouldBe "PRIVACY_NOTICE_DUE"
        TRIGGER_PRIVACY_NOTICE_DUE shouldBe "PRIVACY_NOTICE_DUE"
        CLASS_SECURITY shouldBe "SECURITY"
        TENANT_WIDE_ENTITY_REF shouldBe "00000000-0000-0000-0000-000000000000"
    }

    @Test
    fun `message holds exactly four properties`() {
        PrivacyNoticeMessageParameters::class.java.declaredFields
            .filter {
                !java.lang.reflect.Modifier
                    .isStatic(it.modifiers)
            }.map { it.name }
            .toSet() shouldBe
            setOf("template", "caseId", "requestKind", "responseDeadline")
        PrivacyNoticeMessage.of(notice()) shouldBe
            PrivacyNoticeMessageParameters(
                "PRIVACY_NOTICE_DUE",
                caseId,
                PrivacyRequestKind.ACCESS,
                LocalDate.of(2026, 3, 31),
            )
    }
}
