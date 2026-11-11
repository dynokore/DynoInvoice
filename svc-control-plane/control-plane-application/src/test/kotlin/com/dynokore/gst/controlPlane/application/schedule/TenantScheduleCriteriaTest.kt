package com.dynokore.gst.controlPlane.application.schedule

import com.dynokore.gst.controlPlane.application.pack.RequestContext
import com.dynokore.gst.controlPlane.application.release.OperatorPermissions
import com.dynokore.gst.controlPlane.domain.commercial.CommercialRef
import com.dynokore.gst.controlPlane.domain.pack.ActorId
import com.dynokore.gst.controlPlane.domain.schedule.CustodyHolder
import com.dynokore.gst.controlPlane.domain.schedule.ScheduleRule
import com.dynokore.gst.controlPlane.domain.schedule.ScheduleState
import com.dynokore.gst.controlPlane.domain.schedule.TenantSchedule
import com.dynokore.platform.serviceruntime.operation.DeclaredOperation
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Unit proof of INV-AC-8101 (second-holder approval) and INV-AC-8103 (approved-schedule change rules) on the real
 * operations against fakes of the repository, the permission port and the clock (INV-REQ-8100).
 */
@Suppress("MaxLineLength", "ktlint:standard:max-line-length")
class TenantScheduleCriteriaTest {
    private val now = Instant.parse("2026-11-20T08:00:00Z")
    private val goLive = Instant.parse("2026-12-01T01:00:00Z")
    private val offboarding = Instant.parse("2027-12-01T01:00:00Z")
    private val tenant = CommercialRef("TEN-7B1004")
    private val clock =
        object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC

            override fun withZone(zone: ZoneId?) = this

            override fun instant() = now
        }

    private var stored: TenantSchedule? = null
    private val repository =
        object : TenantScheduleRepository {
            override fun find(tenantRef: CommercialRef) = stored?.takeIf { it.tenantRef == tenantRef }

            override fun insert(
                schedule: TenantSchedule,
                setAt: Instant,
            ) {
                stored = schedule
            }

            override fun update(schedule: TenantSchedule) {
                stored = schedule
            }

            override fun dueForOpening(
                now: Instant,
                limit: Int,
            ) = listOfNotNull(
                stored
                    ?.takeIf {
                        it.state == ScheduleState.APPROVED && it.openedAt == null && !it.goLiveAt.isAfter(now)
                    }?.tenantRef,
            ).take(limit)

            override fun recordOpenAttempt(
                tenantRef: CommercialRef,
                at: Instant,
            ) {
                stored = stored?.takeIf { it.tenantRef == tenantRef }?.copy(lastOpenAttemptAt = at) ?: stored
            }

            override fun lock(tenantRef: CommercialRef) = find(tenantRef)
        }

    private val allKeys =
        setOf(
            SchedulePermission.SET,
            SchedulePermission.CHANGE,
            SchedulePermission.CANCEL,
            SchedulePermission.READ,
            SchedulePermission.APPROVE,
        )
    private val grants =
        mutableMapOf("alice" to allKeys, "bob" to allKeys, "carol" to setOf("release-train.publish"))
    private val permissions = OperatorPermissions { actor, key -> key in grants[actor].orEmpty() }
    private val refs = listOf("SCH-1", "SCH-2").iterator()
    private val set = SetTenantSchedule(repository, permissions, clock) { refs.next() }
    private val approve = ApproveTenantSchedule(repository, permissions, clock)
    private val change = ChangeTenantSchedule(repository, permissions, clock)
    private val cancel = CancelTenantSchedule(repository, permissions)

    private fun request() = RequestContext("corr", "cause")

    /** What the operation runner does: permission, repeat, precondition, effect. Returns refusal or result. */
    private fun <C, S, R> run(
        operation: DeclaredOperation<C, S, R>,
        actor: String,
        command: C,
    ): Pair<String?, R?> {
        val state = operation.load(command)
        val prior = operation.priorResult(command, state)
        val refusal = if (prior == null) operation.unmetPrecondition(command, state) else null
        return when {
            !operation.permits(actor, command) -> "NOT_PERMITTED" to null
            prior != null -> null to prior
            refusal != null -> refusal to null
            else -> null to operation.perform(command, state)
        }
    }

    private fun setBy(
        actor: String,
        contract: String? = "CTR-2026-0091",
        off: Instant = offboarding,
    ) = run(
        set,
        actor,
        SetTenantScheduleCommand(
            ActorId(actor),
            tenant,
            contract,
            goLive,
            off,
            CustodyHolder("Custodian", "c@x.example", null),
            null,
            request(),
        ),
    )

    private fun approveCommand(
        actor: String,
        target: ApproveTenantScheduleCommand.Target = ApproveTenantScheduleCommand.Target.SCHEDULE,
    ) = ApproveTenantScheduleCommand(ActorId(actor), tenant, target, request())

    private fun approveBy(
        actor: String,
        target: ApproveTenantScheduleCommand.Target = ApproveTenantScheduleCommand.Target.SCHEDULE,
    ) = run(approve, actor, approveCommand(actor, target))

    private fun changeBy(
        actor: String,
        goLiveAt: Instant? = null,
        offboardingAt: Instant? = null,
    ) = run(
        change,
        actor,
        ChangeTenantScheduleCommand(ActorId(actor), tenant, "SCH-1", goLiveAt, offboardingAt, request()),
    )

    private fun cancelBy(actor: String) =
        run(cancel, actor, CancelTenantScheduleCommand(ActorId(actor), tenant, request()))

    @Test
    @DisplayName(
        "INV-AC-8101 the operator who set a schedule cannot approve it and a person without the approve key is " +
            "refused and the schedule stays awaiting approval",
    )
    fun `INV-AC-8101 the operator who set a schedule cannot approve it and a person without the approve key is refused and the schedule stays awaiting approval`() {
        setBy("alice").second?.state shouldBe ScheduleState.AWAITING_APPROVAL
        stored?.setBy shouldBe "alice"
        approveBy("alice").first shouldBe ScheduleRefusal.SELF_APPROVAL
        approve.permits("carol", approveCommand("carol")) shouldBe false
        approveBy("carol").first shouldBe "NOT_PERMITTED"
        stored?.state shouldBe ScheduleState.AWAITING_APPROVAL
        stored?.approvedBy shouldBe null
        repository.dueForOpening(goLive.plusSeconds(60), 10) shouldBe emptyList()
    }

    @Test
    @DisplayName(
        "INV-AC-8101 a second holder of the approve key approves, the stored approver is that holder and a " +
            "principal differing by one character or by case is a different person",
    )
    fun `INV-AC-8101 a second holder of the approve key approves, the stored approver is that holder and a principal differing by one character or by case is a different person`() {
        setBy("alice")
        for (other in listOf("alicf", "Alice", "alice ")) {
            approve.unmetPrecondition(approveCommand(other), stored) shouldBe null
        }
        approve.unmetPrecondition(approveCommand("alice"), stored) shouldBe ScheduleRefusal.SELF_APPROVAL
        val before = stored
        val command = approveCommand("bob")
        val approved = approveBy("bob").second
        stored?.state shouldBe ScheduleState.APPROVED
        stored?.approvedBy shouldBe "bob"
        approve.audit(command, before, checkNotNull(approved)).reason shouldBe
            "tenant=TEN-7B1004 contract=CTR-2026-0091"
        repository.dueForOpening(goLive, 10) shouldBe listOf(tenant)
    }

    @Test
    @DisplayName(
        "INV-AC-8101 a schedule without a contract reference or with offboarding equal to go-live is refused " +
            "when set and nothing is stored",
    )
    fun `INV-AC-8101 a schedule without a contract reference or with offboarding equal to go-live is refused when set and nothing is stored`() {
        setBy("alice", contract = null).first shouldBe ScheduleRule.MISSING_CONTRACT
        setBy("alice", contract = " ").first shouldBe ScheduleRule.MISSING_CONTRACT
        setBy("alice", off = goLive).first shouldBe ScheduleRule.OFFBOARDING_NOT_AFTER_GO_LIVE
        stored shouldBe null
    }

    @Test
    @DisplayName(
        "INV-AC-8103 a change and a cancellation wait for a person other than the requester and the setter and " +
            "the approved schedule stays in force until then",
    )
    fun `INV-AC-8103 a change and a cancellation wait for a person other than the requester and the setter and the approved schedule stays in force until then`() {
        setBy("alice")
        approveBy("bob")
        val newOff = offboarding.plusSeconds(86400)
        changeBy("alice", offboardingAt = newOff).second?.offboardingAt shouldBe offboarding
        cancelBy("alice").second?.state shouldBe ScheduleState.APPROVED
        stored?.offboardingAt shouldBe offboarding
        stored?.state shouldBe ScheduleState.APPROVED
        approveBy("alice", ApproveTenantScheduleCommand.Target.CHANGE).first shouldBe ScheduleRefusal.SELF_APPROVAL
        approveBy("alice", ApproveTenantScheduleCommand.Target.CANCELLATION).first shouldBe
            ScheduleRefusal.SELF_APPROVAL
        stored?.offboardingAt shouldBe offboarding
        approveBy("bob", ApproveTenantScheduleCommand.Target.CHANGE).second?.offboardingAt shouldBe newOff
        stored?.offboardingAt shouldBe newOff
        approveBy("bob", ApproveTenantScheduleCommand.Target.CANCELLATION).second?.state shouldBe
            ScheduleState.CANCELLED
        stored?.state shouldBe ScheduleState.CANCELLED
    }

    @Test
    @DisplayName(
        "INV-AC-8103 the change of a go-live time that was carried out is refused and the stored schedule is " +
            "unchanged",
    )
    fun `INV-AC-8103 the change of a go-live time that was carried out is refused and the stored schedule is unchanged`() {
        setBy("alice")
        approveBy("bob")
        stored = stored?.copy(openedAt = goLive)
        stored?.goLiveCarriedOut shouldBe true
        val before = stored
        changeBy("alice", goLiveAt = goLive.plusSeconds(86400)).first shouldBe ScheduleRefusal.CARRIED_OUT
        stored shouldBe before
    }
}
