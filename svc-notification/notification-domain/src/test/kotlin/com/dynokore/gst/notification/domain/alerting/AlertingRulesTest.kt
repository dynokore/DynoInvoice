package com.dynokore.gst.notification.domain.alerting

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

private const val JANE = "tax.approver:jane.tan"

class AlertingRulesTest {
    private val bizDate = LocalDate.of(2026, 4, 20)
    private val now = Instant.parse("2026-04-20T00:00:00Z")
    private val stop = Instant.parse("2026-04-27T00:00:00Z")

    private fun rec(
        r: List<String>,
        c: AcknowledgementClaim? = null,
    ) = RecipientRule.decide(r, c, bizDate)

    private fun refusedR(
        code: String,
        why: RecipientRefusal,
    ) = RecipientDecision.Refused(code, why)

    private fun snooze(
        c: AlertClass,
        until: Instant,
        hard: Instant? = stop,
    ) = SnoozeRule.decide(c, now, until, hard)

    private data class Bounce(
        val c: AlertClass = AlertClass.DEADLINE,
        val ack: Boolean = false,
        val r: List<String> = listOf(JANE),
        val b: List<String> = listOf(JANE),
        val cur: Int = 1,
        val high: Int = 2,
    )

    private fun esc(e: Bounce = Bounce()) = BounceEscalation.decide(e.c, e.ack, e.r, e.b, e.cur, e.high)

    @Test
    fun `INV-AC-2115 one recipient with no claim is refused POL-014`() {
        rec(listOf(JANE)) shouldBe refusedR("POL-014", RecipientRefusal.TOO_FEW_RECIPIENTS)
    }

    @Test
    fun `INV-AC-2115 a claim naming the person with no date is saved from the business date`() {
        rec(listOf(JANE), AcknowledgementClaim(JANE, null)) shouldBe
            RecipientDecision.Saved(RecordedAcknowledgement(JANE, bizDate))
    }

    @Test
    fun `two distinct recipients are saved with no acknowledgement whatever the claim says`() {
        rec(listOf("a", "b"), AcknowledgementClaim(" ", bizDate.minusDays(5))) shouldBe RecipientDecision.Saved(null)
    }

    @Test
    fun `equal recipients count as one and different case counts as two`() {
        rec(listOf("a", "a")) shouldBe refusedR("POL-014", RecipientRefusal.TOO_FEW_RECIPIENTS)
        rec(listOf("a", "A")) shouldBe RecipientDecision.Saved(null)
    }

    @Test
    fun `a blank recipient is refused first`() {
        rec(listOf("a", "  "), AcknowledgementClaim(JANE, null)) shouldBe
            refusedR("POL-013", RecipientRefusal.BLANK_RECIPIENT)
    }

    @Test
    fun `a blank or null person is refused`() {
        rec(listOf("a"), AcknowledgementClaim(" ", null)) shouldBe
            refusedR("POL-013", RecipientRefusal.ACCOUNTABLE_PERSON_MISSING)
        rec(listOf("a"), AcknowledgementClaim(null, null)) shouldBe
            refusedR("POL-013", RecipientRefusal.ACCOUNTABLE_PERSON_MISSING)
    }

    @Test
    fun `an effective date before the business date is back dated and the same day is accepted`() {
        rec(listOf("a"), AcknowledgementClaim(JANE, bizDate.minusDays(1))) shouldBe
            refusedR("POL-022", RecipientRefusal.BACK_DATED)
        rec(listOf("a"), AcknowledgementClaim(JANE, bizDate)) shouldBe
            RecipientDecision.Saved(RecordedAcknowledgement(JANE, bizDate))
    }

    @Test
    fun `INV-AC-103 deadline with an empty channel list is refused POL-015`() {
        DisableRule.decide(AlertClass.DEADLINE, emptyList()) shouldBe DisableDecision.Refused("POL-015")
    }

    @Test
    fun `INV-AC-103 INV-AC-185 deadline with EMAIL is allowed`() {
        DisableRule.decide(AlertClass.DEADLINE, listOf("EMAIL")) shouldBe DisableDecision.Allowed
    }

    @Test
    fun `INV-AC-185 coverage with an empty channel list is refused`() {
        DisableRule.decide(AlertClass.COVERAGE, emptyList()) shouldBe DisableDecision.Refused("POL-015")
    }

    @Test
    fun `other classes may have an empty channel list`() {
        listOf(AlertClass.BACKLOG, AlertClass.MISSING_ACKNOWLEDGEMENT, AlertClass.PACK_STALENESS, AlertClass.SECURITY)
            .forEach { DisableRule.decide(it, emptyList()) shouldBe DisableDecision.Allowed }
    }

    @Test
    fun `INV-AC-2114 a snooze of 7 days is accepted and of 9 days is refused`() {
        val seven = now.plus(Duration.ofDays(7))
        snooze(AlertClass.DEADLINE, seven) shouldBe SnoozeDecision.Accepted(seven)
        snoozeNineDays()
    }

    private fun snoozeNineDays() {
        snooze(AlertClass.DEADLINE, now.plus(Duration.ofDays(9))) shouldBe
            SnoozeDecision.Refused("POL-013", SnoozeRefusal.BEYOND_MAX_SNOOZE)
    }

    @Test
    fun `deadline snooze past the hard stop is refused and exactly at it is accepted`() {
        val hard = Instant.parse("2026-04-22T00:00:00Z")
        snooze(AlertClass.DEADLINE, Instant.parse("2026-04-25T00:00:00Z"), hard) shouldBe
            SnoozeDecision.Refused("POL-013", SnoozeRefusal.BEYOND_HARD_STOP)
        snooze(AlertClass.DEADLINE, hard, hard) shouldBe SnoozeDecision.Accepted(hard)
    }

    @Test
    fun `deadline and coverage with no hard stop are refused POL-014`() {
        val until = now.plus(Duration.ofDays(1))
        listOf(AlertClass.DEADLINE, AlertClass.COVERAGE).forEach {
            snooze(it, until, null) shouldBe SnoozeDecision.Refused("POL-014", SnoozeRefusal.HARD_STOP_UNKNOWN)
        }
    }

    @Test
    fun `backlog ignores the hard stop and is bounded by 7 days`() {
        val seven = now.plus(Duration.ofDays(7))
        snooze(AlertClass.BACKLOG, seven, null) shouldBe SnoozeDecision.Accepted(seven)
        snooze(AlertClass.BACKLOG, seven.plusSeconds(1), null) shouldBe
            SnoozeDecision.Refused("POL-013", SnoozeRefusal.BEYOND_MAX_SNOOZE)
        snooze(AlertClass.BACKLOG, seven, now) shouldBe SnoozeDecision.Accepted(seven)
    }

    @Test
    fun `a snooze equal to now is not in the future`() {
        snooze(AlertClass.BACKLOG, now, null) shouldBe SnoozeDecision.Refused("POL-013", SnoozeRefusal.NOT_IN_FUTURE)
    }

    @Test
    fun `INV-AC-1872 INV-AC-10575 bounced sole deadline recipient at tier 1 of 2 escalates to 2`() {
        esc() shouldBe EscalationDecision.Escalate(2)
    }

    @Test
    fun `INV-AC-10575 escalation stays for every other case`() {
        esc(Bounce(c = AlertClass.BACKLOG)) shouldBe EscalationDecision.Stay(NoEscalation.NOT_DEADLINE)
        esc(Bounce(ack = true)) shouldBe EscalationDecision.Stay(NoEscalation.ACKNOWLEDGED)
        val two = listOf("a", "b")
        esc(Bounce(r = two, b = two)) shouldBe EscalationDecision.Stay(NoEscalation.NOT_ONE_RECIPIENT)
        esc(Bounce(r = emptyList())) shouldBe EscalationDecision.Stay(NoEscalation.NOT_ONE_RECIPIENT)
        esc(Bounce(b = emptyList())) shouldBe EscalationDecision.Stay(NoEscalation.NOT_BOUNCED)
        esc(Bounce(cur = 2)) shouldBe EscalationDecision.Stay(NoEscalation.NO_NEXT_TIER)
    }

    @Test
    fun `duplicate recipient entries count as one for escalation`() {
        esc(Bounce(r = listOf(JANE, JANE))) shouldBe EscalationDecision.Escalate(2)
    }

    @Test
    fun `fromKey accepts the six keys and refuses look-alikes`() {
        AlertClass.entries.forEach { AlertClass.fromKey(it.name) shouldBe it }
        listOf("deadline", "DEADLINES", "").forEach { shouldThrow<IllegalArgumentException> { AlertClass.fromKey(it) } }
    }

    @Test
    fun `enums and constants have exactly the specified members`() {
        AlertClass.entries.map { it.name }.toSet() shouldBe
            setOf("COVERAGE", "DEADLINE", "BACKLOG", "MISSING_ACKNOWLEDGEMENT", "PACK_STALENESS", "SECURITY")
        RecipientRefusal.entries.map { it.name }.toSet() shouldBe
            setOf("BLANK_RECIPIENT", "TOO_FEW_RECIPIENTS", "ACCOUNTABLE_PERSON_MISSING", "BACK_DATED")
        SnoozeRefusal.entries.map { it.name }.toSet() shouldBe
            setOf("NOT_IN_FUTURE", "BEYOND_MAX_SNOOZE", "BEYOND_HARD_STOP", "HARD_STOP_UNKNOWN")
        NoEscalation.entries.map { it.name }.toSet() shouldBe
            setOf("NOT_DEADLINE", "ACKNOWLEDGED", "NOT_ONE_RECIPIENT", "NOT_BOUNCED", "NO_NEXT_TIER")
        listOf(POL_OUT_OF_BOUNDS, POL_CONSTRAINT, POL_NOT_DISABLEABLE, POL_BACK_DATED, MAX_SNOOZE_DAYS) shouldBe
            listOf("POL-013", "POL-014", "POL-015", "POL-022", 7L)
    }
}
