package com.dynokore.gst.notification.application.alerting

import com.dynokore.gst.notification.domain.alerting.AcknowledgementClaim
import com.dynokore.gst.notification.domain.alerting.AlertClass
import com.dynokore.gst.notification.domain.alerting.RecordedAcknowledgement
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.DecisionReason
import com.dynokore.platform.serviceruntime.replica.Grant
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.replica.PrincipalState
import com.dynokore.platform.serviceruntime.replica.ScopeLevel
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

internal val TENANT = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
internal const val ENTITY = "e1"
internal val PERMITTED = Principal("permitted", PrincipalKind.HUMAN)
internal val NO_GRANT = Principal("nobody", PrincipalKind.HUMAN)
internal val REVOKED = Principal("revoked", PrincipalKind.HUMAN)

internal class TestClock(
    private var now: Instant = Instant.parse("2026-04-20T00:00:00Z"),
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = now

    fun advance(by: Duration) {
        now = now.plus(by)
    }
}

/** A replica fed with one grant of [action] for [PERMITTED], and a revoked grant for [REVOKED]. */
internal class ReplicaFixture(
    action: String,
) {
    val clock = TestClock()
    val replica = AuthorityReplica(clock)
    private var version = 0L

    init {
        replica.onSnapshotComplete(0)
        val grant = Grant(action, ScopeLevel.ENTITY, ENTITY, clock.instant().minusSeconds(60))
        for (p in listOf(PERMITTED, REVOKED)) {
            replica.accept(
                AuthorityChanged(TENANT, p.id, ++version, AuthorityRecord.GrantRecord(ENTITY, listOf(grant))),
            )
        }
        replica.accept(
            AuthorityChanged(TENANT, REVOKED.id, ++version, AuthorityRecord.PrincipalRecord(PrincipalState.REVOKED)),
        )
    }

    /** A replica of the same grants whose feed lags past the bound with no fallback. */
    fun lagging(): AuthorityReplica {
        val lagged = AuthorityReplica(clock)
        lagged.onSnapshotComplete(0)
        lagged.observeHead(version + 5)
        clock.advance(lagged.lagBound.plusSeconds(1))
        return lagged
    }

    fun deadline(): Deadline = Deadline.after(Duration.ofSeconds(30), clock)
}

class SetAlertPolicyTest {
    private val fixture = ReplicaFixture(AlertActions.SET_ALERT_POLICY)
    private val businessDate = LocalDate.parse("2026-04-20")
    private val service = SetAlertPolicy(fixture.replica)

    private fun command(
        recipients: List<String>,
        claim: AcknowledgementClaim? = null,
        alertClass: AlertClass = AlertClass.BACKLOG,
        channels: List<String> = listOf("EMAIL"),
        principal: Principal = PERMITTED,
    ) = SetAlertPolicyCommand(TENANT, principal, ENTITY, alertClass, recipients, channels, claim, businessDate)

    private fun handle(command: SetAlertPolicyCommand) = service.handle(command, fixture.deadline())

    private fun claim(
        person: String?,
        date: String? = null,
    ) = AcknowledgementClaim(person, date?.let(LocalDate::parse))

    @Test
    fun `INV-AC-10574 one recipient and no claim is refused POL-014 TOO_FEW_RECIPIENTS`() {
        handle(command(listOf("jane"))) shouldBe SetAlertPolicyResult.Refused("POL-014", "TOO_FEW_RECIPIENTS")
    }

    @Test
    fun `INV-AC-10574 a named person with no date is accepted effective on the business date`() {
        handle(command(listOf("jane"), claim("jane"))) shouldBe
            SetAlertPolicyResult.Accepted(RecordedAcknowledgement("jane", businessDate))
    }

    @Test
    fun `INV-AC-10574 a back-dated claim is refused POL-022 and a same-day claim is accepted`() {
        handle(command(listOf("jane"), claim("jane", "2026-04-19"))) shouldBe
            SetAlertPolicyResult.Refused("POL-022", "BACK_DATED")
        handle(command(listOf("jane"), claim("jane", "2026-04-20"))) shouldBe
            SetAlertPolicyResult.Accepted(RecordedAcknowledgement("jane", businessDate))
    }

    @Test
    fun `INV-AC-10574 a blank person is refused POL-013 ACCOUNTABLE_PERSON_MISSING`() {
        handle(command(listOf("jane"), claim(" "))) shouldBe
            SetAlertPolicyResult.Refused("POL-013", "ACCOUNTABLE_PERSON_MISSING")
    }

    @Test
    fun `INV-AC-10574 two distinct recipients are accepted with no acknowledgement`() {
        handle(command(listOf("jane", "tom"))) shouldBe SetAlertPolicyResult.Accepted(null)
    }

    @Test
    fun `INV-AC-10574 a duplicate counts as one recipient and a different case as two`() {
        handle(command(listOf("jane", "jane"))) shouldBe SetAlertPolicyResult.Refused("POL-014", "TOO_FEW_RECIPIENTS")
        handle(command(listOf("jane", "Jane"))) shouldBe SetAlertPolicyResult.Accepted(null)
    }

    @Test
    fun `INV-AC-10574 a blank entry is refused POL-013 BLANK_RECIPIENT`() {
        handle(command(listOf("jane", " "))) shouldBe SetAlertPolicyResult.Refused("POL-013", "BLANK_RECIPIENT")
    }

    @Test
    fun `INV-AC-10574 a principal with no grant is Forbidden NO_GRANT before any rule`() {
        // one recipient and no claim: the rules alone would refuse with POL-014
        handle(command(listOf("jane"), principal = NO_GRANT)) shouldBe
            SetAlertPolicyResult.Forbidden(DecisionReason.NO_GRANT)
    }

    @Test
    fun `INV-AC-10574 a revoked grant is Forbidden REVOKED before any rule`() {
        handle(command(listOf("jane"), principal = REVOKED)) shouldBe
            SetAlertPolicyResult.Forbidden(DecisionReason.REVOKED)
    }

    @Test
    fun `INV-AC-10574 a lagging replica with no fallback is Forbidden AUTHORITY_UNAVAILABLE before any rule`() {
        val lagging = SetAlertPolicy(fixture.lagging())
        // empty channels on DEADLINE: the disable rule alone would refuse with POL-015
        val input = command(listOf("jane"), alertClass = AlertClass.DEADLINE, channels = emptyList())
        lagging.handle(input, fixture.deadline()) shouldBe
            SetAlertPolicyResult.Forbidden(DecisionReason.AUTHORITY_UNAVAILABLE)
    }

    @Test
    fun `INV-AC-10573 DEADLINE and COVERAGE with no channel are refused POL-015 and the rest accepted`() {
        for (alertClass in listOf(AlertClass.DEADLINE, AlertClass.COVERAGE, AlertClass.BACKLOG)) {
            val empty = handle(command(listOf("jane", "tom"), alertClass = alertClass, channels = emptyList()))
            empty shouldBe
                if (alertClass == AlertClass.BACKLOG) {
                    SetAlertPolicyResult.Accepted(null)
                } else {
                    SetAlertPolicyResult.Refused("POL-015", "NOT_DISABLEABLE")
                }
            handle(command(listOf("jane", "tom"), alertClass = alertClass, channels = listOf("EMAIL"))) shouldBe
                SetAlertPolicyResult.Accepted(null)
        }
    }

    @Test
    fun `INV-AC-10573 a refused principal is Forbidden for every class and channel list`() {
        for (alertClass in listOf(AlertClass.DEADLINE, AlertClass.COVERAGE, AlertClass.BACKLOG)) {
            for (channels in listOf(emptyList(), listOf("EMAIL"))) {
                handle(
                    command(listOf("jane", "tom"), alertClass = alertClass, channels = channels, principal = NO_GRANT),
                ) shouldBe
                    SetAlertPolicyResult.Forbidden(DecisionReason.NO_GRANT)
            }
        }
    }

    @Test
    fun `INV-AC-10573 the action keys are exactly the two catalogue candidates`() {
        AlertActions.SET_ALERT_POLICY shouldBe "alert-policy.set"
        AlertActions.SNOOZE_ALERT shouldBe "alert.snooze"
        AlertActions::class.java.declaredFields
            .filter {
                java.lang.reflect.Modifier
                    .isStatic(it.modifiers) && it.name != "INSTANCE"
            }.map { it.name }
            .toSet() shouldBe setOf("SET_ALERT_POLICY", "SNOOZE_ALERT")
    }
}
