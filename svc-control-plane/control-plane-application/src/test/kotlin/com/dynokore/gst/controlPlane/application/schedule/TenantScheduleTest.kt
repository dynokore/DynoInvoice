package com.dynokore.gst.controlPlane.application.schedule

import com.dynokore.gst.controlPlane.application.pack.RequestContext
import com.dynokore.gst.controlPlane.application.release.OperatorPermissions
import com.dynokore.gst.controlPlane.domain.commercial.CommercialRef
import com.dynokore.gst.controlPlane.domain.pack.ActorId
import com.dynokore.gst.controlPlane.domain.schedule.CustodyHolder
import com.dynokore.gst.controlPlane.domain.schedule.PendingKind
import com.dynokore.gst.controlPlane.domain.schedule.PendingRequest
import com.dynokore.gst.controlPlane.domain.schedule.ScheduleRule
import com.dynokore.gst.controlPlane.domain.schedule.ScheduleState
import com.dynokore.gst.controlPlane.domain.schedule.TenantSchedule
import com.dynokore.platform.serviceruntime.operation.DeclaredOperation
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** The tenant schedule operations and the opening step against fakes of their ports (INV-REQ-8100). */
class TenantScheduleTest {
    private val t0 = Instant.parse("2026-11-20T08:00:00Z")
    private val goLive = GO_LIVE
    private val offboarding = OFFBOARDING
    private val tenant = CommercialRef("TEN-7B1004")
    private var now = t0
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
            )

            override fun recordOpenAttempt(
                tenantRef: CommercialRef,
                at: Instant,
            ) {
                stored = stored?.takeIf { it.tenantRef == tenantRef }?.copy(lastOpenAttemptAt = at) ?: stored
            }

            override fun lock(tenantRef: CommercialRef) = find(tenantRef)
        }

    /** The five schedule keys; alice and bob hold them all, carol only a key of another operator role. */
    private val allKeys =
        setOf(
            SchedulePermission.SET,
            SchedulePermission.CHANGE,
            SchedulePermission.CANCEL,
            SchedulePermission.READ,
            SchedulePermission.APPROVE,
        )
    private val grants = mutableMapOf("alice" to allKeys, "bob" to allKeys, "carol" to setOf("release-train.publish"))
    private val asked = ArrayList<Pair<String, String>>()
    private val permissions =
        OperatorPermissions { actor, key ->
            asked += actor to key
            key in grants[actor].orEmpty()
        }
    private val refs = listOf("SCH-1", "SCH-2").iterator()
    private val set = SetTenantSchedule(repository, permissions, clock) { refs.next() }
    private val approve = ApproveTenantSchedule(repository, permissions, clock)
    private val change = ChangeTenantSchedule(repository, permissions, clock)
    private val cancel = CancelTenantSchedule(repository, permissions)

    private val opens = ArrayList<Pair<String, String>>()
    private var openerAnswer = TenantOpening.OPENED
    private val audits = ArrayList<Triple<TenantSchedule, String, Instant>>()
    private val job =
        OpenDueTenants(
            repository,
            { t, s ->
                opens += t.value to s
                openerAnswer
            },
            { s, a, at -> audits += Triple(s, a, at) },
            object : TransactionRunner {
                override fun <T> inTransaction(block: () -> T): T = block()
            },
            clock,
        )

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
        at: Instant = goLive,
        off: Instant = offboarding,
    ) = run(
        set,
        actor,
        SetTenantScheduleCommand(
            ActorId(actor),
            tenant,
            contract,
            at,
            off,
            CustodyHolder("Custodian", "c@x.example", null),
            null,
            request(),
        ),
    )

    private fun approveBy(
        actor: String,
        target: ApproveTenantScheduleCommand.Target = ApproveTenantScheduleCommand.Target.SCHEDULE,
    ) = run(approve, actor, ApproveTenantScheduleCommand(ActorId(actor), tenant, target, request()))

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
    fun `INV-AC-19862 the operator cannot approve their own schedule and a holder without the key is refused`() {
        setBy("alice").second?.state shouldBe ScheduleState.AWAITING_APPROVAL
        stored?.setBy shouldBe "alice"
        approveBy("alice").first shouldBe ScheduleRefusal.SELF_APPROVAL
        approveBy("carol").first shouldBe "NOT_PERMITTED"
        stored?.state shouldBe ScheduleState.AWAITING_APPROVAL
        stored?.approvedBy shouldBe null
        job.runOnce(10) shouldBe 0
    }

    @Test
    fun `INV-AC-19862 a second holder approves and the approval is the stored approver`() {
        setBy("alice")
        val approved = approveBy("bob").second
        approved?.state shouldBe ScheduleState.APPROVED
        approved?.approvedBy shouldBe "bob"
        asked.contains("bob" to "tenant-schedule.approve") shouldBe true
    }

    @Test
    fun `INV-AC-19862 a schedule without a contract reference or with offboarding equal to go-live is refused`() {
        setBy("alice", contract = null).first shouldBe ScheduleRule.MISSING_CONTRACT
        setBy("alice", contract = " ").first shouldBe ScheduleRule.MISSING_CONTRACT
        setBy("alice", off = goLive).first shouldBe ScheduleRule.OFFBOARDING_NOT_AFTER_GO_LIVE
        setBy("alice", at = t0).first shouldBe ScheduleRule.GO_LIVE_NOT_AFTER_NOW
        stored shouldBe null
    }

    @Test
    fun `INV-AC-19862 probe the key of another operator role does not approve`() {
        setBy("alice")
        approveBy("carol").first shouldBe "NOT_PERMITTED"
        asked.last() shouldBe ("carol" to "tenant-schedule.approve")
        stored?.state shouldBe ScheduleState.AWAITING_APPROVAL
    }

    @Test
    fun `INV-AC-19862 probe a look-alike principal is a different person and the same principal is refused`() {
        setBy("alice")
        grants["alice2"] = allKeys
        grants["Alice"] = allKeys
        approveBy("alice").first shouldBe ScheduleRefusal.SELF_APPROVAL
        // equality, not prefix or case folding: "alice2" is another person
        approveBy("alice2").second?.state shouldBe ScheduleState.APPROVED
    }

    @Test
    fun `INV-AC-19862 probe the last character differs and the case differs`() {
        setBy("alice")
        grants["alicf"] = allKeys
        approveBy("alicf").second?.approvedBy shouldBe "alicf"
        stored = stored?.copy(state = ScheduleState.AWAITING_APPROVAL, approvedBy = null)
        grants["ALICE"] = allKeys
        approveBy("ALICE").second?.approvedBy shouldBe "ALICE"
    }

    @Test
    fun `INV-AC-19862 probe the second approval of the same schedule changes nothing`() {
        setBy("alice")
        val first = approveBy("bob").second
        grants["dave"] = allKeys
        val again = approveBy("dave")
        again.first shouldBe null
        again.second shouldBe first
        stored?.approvedBy shouldBe "bob"
    }

    @Test
    fun `INV-AC-19862 probe one second before go-live no call, two runs at go-live one call and one audit`() {
        setBy("alice")
        approveBy("bob")
        now = goLive.minusSeconds(1)
        job.runOnce(10) shouldBe 0
        opens.size shouldBe 0
        now = goLive
        job.runOnce(10) shouldBe 1
        job.runOnce(10) shouldBe 0
        now = goLive.plusSeconds(3600)
        job.runOnce(10) shouldBe 0
        opens shouldBe listOf("TEN-7B1004" to "SCH-1")
        stored?.openedAt shouldBe goLive
        audits.single().second shouldBe "openTenant"
        audits.single().third shouldBe goLive
        audits
            .single()
            .first.contractReference.value shouldBe "CTR-2026-0091"
        OpenDueTenants.ACTOR shouldBe "job:tenant-schedule-opening"
    }

    @Test
    fun `INV-AC-19862 probe an AWAITING_APPROVAL schedule causes no call even after its go-live time`() {
        setBy("alice")
        now = goLive.plusSeconds(10)
        job.runOnce(10) shouldBe 0
        opens.size shouldBe 0
        audits.size shouldBe 0
    }

    @Test
    fun `INV-AC-19862 a failed openTenant call changes nothing and the next run calls again`() {
        setBy("alice")
        approveBy("bob")
        now = goLive
        openerAnswer = TenantOpening.NOT_OPENED
        job.runOnce(10) shouldBe 1
        stored?.openedAt shouldBe null
        audits.size shouldBe 0
        openerAnswer = TenantOpening.OPENED
        job.runOnce(10) shouldBe 1
        stored?.openedAt shouldBe goLive
        audits.size shouldBe 1
    }

    @Test
    fun `INV-AC-19862 a change and a cancellation wait for a different holder, the approved one stays in force`() {
        setBy("alice")
        approveBy("bob")
        now = goLive.plusSeconds(60)
        job.runOnce(10)
        val newOff = offboarding.plusSeconds(86400)
        changeBy("alice", offboardingAt = newOff).second?.offboardingAt shouldBe offboarding
        cancelBy("alice").second?.state shouldBe ScheduleState.APPROVED
        approveBy("alice", ApproveTenantScheduleCommand.Target.CHANGE).first shouldBe ScheduleRefusal.SELF_APPROVAL
        approveBy("alice", ApproveTenantScheduleCommand.Target.CANCELLATION).first shouldBe
            ScheduleRefusal.SELF_APPROVAL
        stored?.offboardingAt shouldBe offboarding
        val changed = approveBy("bob", ApproveTenantScheduleCommand.Target.CHANGE).second
        changed?.offboardingAt shouldBe newOff
        changed?.state shouldBe ScheduleState.APPROVED
        (stored?.pendingCancellation != null) shouldBe true
        approveBy("bob", ApproveTenantScheduleCommand.Target.CANCELLATION).second?.state shouldBe
            ScheduleState.CANCELLED
    }

    @Test
    fun `INV-AC-19862 the change of a go-live time that was carried out is refused and changes nothing`() {
        setBy("alice")
        approveBy("bob")
        now = goLive
        job.runOnce(10)
        val before = stored
        changeBy("alice", goLiveAt = goLive.plusSeconds(86400)).first shouldBe ScheduleRefusal.CARRIED_OUT
        stored shouldBe before
    }

    @Test
    fun `INV-AC-19862 a change of the times of a schedule not yet approved is refused and a bad pair is 422`() {
        setBy("alice")
        changeBy("alice", offboardingAt = offboarding.plusSeconds(1)).first shouldBe ScheduleRefusal.NOT_APPROVED
        approveBy("bob")
        changeBy("alice", offboardingAt = goLive).first shouldBe ScheduleRule.OFFBOARDING_NOT_AFTER_GO_LIVE
        changeBy("alice").first shouldBe ScheduleRefusal.NO_TIME
    }

    private val oneKeyOnly = { key: String -> setOf(key) }

    @Test
    fun `INV-AC-19862 each operation is decided by its own key and an actor with only another key is refused`() {
        setBy("alice")
        approveBy("bob")
        val keys =
            mapOf(
                SchedulePermission.SET to { a: String -> setBy(a, contract = "CTR-OTHER").first },
                SchedulePermission.CHANGE to
                    { a: String -> changeBy(a, offboardingAt = offboarding.plusSeconds(60)).first },
                SchedulePermission.CANCEL to { a: String -> cancelBy(a).first },
                SchedulePermission.APPROVE to
                    { a: String -> approveBy(a, ApproveTenantScheduleCommand.Target.CHANGE).first },
            )
        val before = stored
        for ((key, call) in keys) {
            for (other in allKeys - key) {
                grants["only"] = oneKeyOnly(other)
                call("only") shouldBe "NOT_PERMITTED"
            }
            // look-alike keys are refused too
            for (alike in listOf(key + "s", key.replaceFirstChar { it.uppercase() })) {
                grants["only"] = oneKeyOnly(alike)
                call("only") shouldBe "NOT_PERMITTED"
            }
        }
        stored shouldBe before
        val get = GetTenantSchedule(repository, permissions)
        grants["only"] = oneKeyOnly(SchedulePermission.SET)
        get.permits("only") shouldBe false
        grants["only"] = oneKeyOnly(SchedulePermission.READ)
        get.permits("only") shouldBe true
        grants["only"] = oneKeyOnly("tenant-schedule.reads")
        get.permits("only") shouldBe false
    }

    @Test
    fun `INV-AC-19862 the key of an operation allows it for an actor allowed exactly that key`() {
        grants["setter"] = oneKeyOnly(SchedulePermission.SET)
        grants["changer"] = oneKeyOnly(SchedulePermission.CHANGE)
        grants["canceller"] = oneKeyOnly(SchedulePermission.CANCEL)
        setBy("setter").second?.setBy shouldBe "setter"
        approveBy("bob")
        changeBy("changer", offboardingAt = offboarding.plusSeconds(60)).second?.pendingChange?.requestedBy shouldBe
            "changer"
        cancelBy("canceller").second?.pendingCancellation?.requestedBy shouldBe "canceller"
    }

    @Test
    fun `INV-AC-19862 a set with the live contract reference returns the original and another one is a 409 conflict`() {
        val first = setBy("alice").second
        setBy("alice").second shouldBe first
        setBy("alice", contract = "CTR-OTHER").first shouldBe ScheduleRefusal.SCHEDULE_EXISTS
        stored shouldBe first
    }

    @Test
    fun `INV-AC-19862 a second change and a second cancellation while one is pending are 409 conflicts`() {
        setBy("alice")
        approveBy("bob")
        val newOff = offboarding.plusSeconds(60)
        changeBy("alice", offboardingAt = newOff).second?.pendingChange?.offboardingAt shouldBe newOff
        changeBy("alice", offboardingAt = offboarding.plusSeconds(120)).first shouldBe ScheduleRefusal.PENDING_EXISTS
        changeBy("bob", offboardingAt = newOff).first shouldBe ScheduleRefusal.PENDING_EXISTS
        cancelBy("alice").second?.pendingCancellation?.requestedBy shouldBe "alice"
        cancelBy("bob").first shouldBe ScheduleRefusal.PENDING_EXISTS
        stored?.pendingChange?.requestedBy shouldBe "alice"
        stored?.pendingCancellation?.requestedBy shouldBe "alice"
    }

    @Test
    fun `INV-AC-19862 a set after the schedule was cancelled is accepted`() {
        setBy("alice")
        approveBy("bob")
        cancelBy("alice")
        approveBy("bob", ApproveTenantScheduleCommand.Target.CANCELLATION).second?.state shouldBe
            ScheduleState.CANCELLED
        refs.hasNext() shouldBe true
        setBy("alice", contract = "CTR-OTHER").second?.state shouldBe ScheduleState.AWAITING_APPROVAL
    }

    @Test
    fun `INV-AC-19862 a failed call stamps the run time and a later success keeps one audit entry`() {
        setBy("alice")
        approveBy("bob")
        now = goLive
        openerAnswer = TenantOpening.NOT_OPENED
        job.runOnce(10) shouldBe 1
        stored?.lastOpenAttemptAt shouldBe goLive
        stored?.copy(lastOpenAttemptAt = null)?.openedAt shouldBe null
        audits.size shouldBe 0
    }

    private fun scheduleWithPendingChange(
        newGoLive: Instant?,
        newOff: Instant?,
    ) {
        setBy("alice")
        approveBy("bob")
        stored = stored?.copy(pendingChange = PendingRequest(PendingKind.CHANGE, newGoLive, newOff, "alice"))
    }

    @Test
    fun `INV-AC-19862 approval re-check refuses a new go-live one second before and equal to the moment`() {
        val newGoLive = goLive.plusSeconds(1000)
        scheduleWithPendingChange(newGoLive, null)
        val before = stored
        val target = ApproveTenantScheduleCommand.Target.CHANGE
        now = newGoLive.plusSeconds(1)
        approveBy("bob", target).first shouldBe ScheduleRefusal.CHANGE_NOT_VALID_NOW
        ScheduleRefusal.CHANGE_NOT_VALID_NOW shouldBe "SCHEDULE_CHANGE_NOT_VALID_NOW"
        now = newGoLive
        approveBy("bob", target).first shouldBe ScheduleRefusal.CHANGE_NOT_VALID_NOW
        stored shouldBe before
        now = newGoLive.minusSeconds(1)
        approveBy("bob", target).second?.goLiveAt shouldBe newGoLive
    }

    @Test
    fun `INV-AC-19862 approval re-check accepts a valid offboarding-only change and refuses a bad pair`() {
        scheduleWithPendingChange(null, offboarding.plusSeconds(3600))
        now = goLive.plusSeconds(10)
        approveBy("bob", ApproveTenantScheduleCommand.Target.CHANGE).second?.offboardingAt shouldBe
            offboarding.plusSeconds(3600)
        stored = stored?.copy(pendingChange = PendingRequest(PendingKind.CHANGE, null, goLive, "alice"))
        val before = stored
        approveBy("bob", ApproveTenantScheduleCommand.Target.CHANGE).first shouldBe
            ScheduleRefusal.CHANGE_NOT_VALID_NOW
        stored shouldBe before
        audits.size shouldBe 0
    }

    @Test
    fun `INV-AC-19862 approval re-check keeps CARRIED_OUT for a go-live that was carried out`() {
        scheduleWithPendingChange(goLive.plusSeconds(99999), null)
        stored = stored?.copy(openedAt = goLive)
        approveBy("bob", ApproveTenantScheduleCommand.Target.CHANGE).first shouldBe ScheduleRefusal.CARRIED_OUT
    }

    @Test
    fun `INV-AC-19862 a change or cancellation of an unapproved schedule is refused NOT_APPROVED and stores nothing`() {
        setBy("alice")
        val awaiting = stored
        changeBy("alice", offboardingAt = offboarding.plusSeconds(1)).first shouldBe ScheduleRefusal.NOT_APPROVED
        cancelBy("alice").first shouldBe ScheduleRefusal.NOT_APPROVED
        stored shouldBe awaiting
        approveBy("bob")
        cancelBy("alice").first shouldBe null
        approveBy("bob", ApproveTenantScheduleCommand.Target.CANCELLATION)
        stored?.state shouldBe ScheduleState.CANCELLED
        val cancelled = stored
        changeBy("alice", offboardingAt = offboarding.plusSeconds(1)).first shouldBe ScheduleRefusal.NOT_APPROVED
        cancelBy("alice").first shouldBe ScheduleRefusal.NOT_APPROVED
        stored shouldBe cancelled
        stored = cancelled?.copy(state = ScheduleState.EXECUTED)
        cancelBy("alice").first shouldBe ScheduleRefusal.NOT_APPROVED
        stored = cancelled?.copy(state = ScheduleState.FAILED)
        cancelBy("alice").first shouldBe ScheduleRefusal.NOT_APPROVED
    }
}

/** The fair opening order of [OpenDueTenants] over a fake with several rows (INV-AC-19862). */
class OpenDueTenantsFairOrderTest {
    private val goLive = GO_LIVE
    private var now = goLive
    private val clock =
        object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC

            override fun withZone(zone: ZoneId?) = this

            override fun instant() = now
        }
    private val audits = ArrayList<Triple<TenantSchedule, String, Instant>>()

    private fun jobOver(
        repo: TenantScheduleRepository,
        opener: TenantOpener,
    ) = OpenDueTenants(
        repo,
        opener,
        { s, a, at -> audits += Triple(s, a, at) },
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T = block()
        },
        clock,
    )

    @Test
    fun `INV-AC-19862 with a batch of two and failing first rows the second run takes the third row`() {
        val repo = ManyRows(listOf(approvedFor("A"), approvedFor("B"), approvedFor("C")))
        val failing = setOf("TEN-A", "TEN-B")
        val called = ArrayList<String>()
        val runner =
            jobOver(repo) { t, _ ->
                called += t.value
                if (t.value in failing) TenantOpening.NOT_OPENED else TenantOpening.OPENED
            }
        now = goLive
        runner.runOnce(2) shouldBe 2
        called shouldBe listOf("TEN-A", "TEN-B")
        called.clear()
        now = goLive.plusSeconds(60)
        runner.runOnce(2) shouldBe 2
        called.first() shouldBe "TEN-C"
        repo.byTenant.getValue(CommercialRef("TEN-C")).openedAt shouldBe now
        // A was tried again in the second slot, so B (stamped earlier) now precedes it
        repo.dueForOpening(now, 10).map { it.value } shouldBe listOf("TEN-B", "TEN-A")
    }

    @Test
    fun `INV-AC-19862 a never-attempted row precedes a row attempted at any time`() {
        val repo =
            ManyRows(
                listOf(
                    approvedFor("A", lastAttempt = Instant.EPOCH),
                    approvedFor("B", lastAttempt = null, at = goLive.plusSeconds(5)),
                    approvedFor("C", lastAttempt = goLive),
                ),
            )
        now = goLive.plusSeconds(10)
        repo.dueForOpening(now, 10).map { it.value } shouldBe listOf("TEN-B", "TEN-A", "TEN-C")
    }

    @Test
    fun `INV-AC-19862 a thrown openTenant call leaves the stamp and the row is not opened`() {
        val repo = ManyRows(listOf(approvedFor("A")))
        val runner = jobOver(repo) { _, _ -> error("tenancy-svc down") }
        now = goLive.plusSeconds(7)
        shouldThrow<IllegalStateException> { runner.runOnce(2) }
        val row = repo.byTenant.getValue(CommercialRef("TEN-A"))
        row.lastOpenAttemptAt shouldBe now
        row.openedAt shouldBe null
        row.copy(lastOpenAttemptAt = null) shouldBe approvedFor("A")
        audits.size shouldBe 0
    }
}

private val GO_LIVE = Instant.parse("2026-12-01T01:00:00Z")
private val OFFBOARDING = Instant.parse("2027-12-01T01:00:00Z")

private fun approvedFor(
    id: String,
    lastAttempt: Instant? = null,
    at: Instant = GO_LIVE,
) = TenantSchedule(
    CommercialRef("TEN-$id"),
    "SCH-$id",
    CommercialRef("CTR-$id"),
    ScheduleState.APPROVED,
    at,
    OFFBOARDING,
    "alice",
    "bob",
    CustodyHolder("Custodian", "c@x.example", null),
    null,
    null,
    lastOpenAttemptAt = lastAttempt,
)

/** A fake with many rows that implements the port order of `dueForOpening`. */
private class ManyRows(
    rows: List<TenantSchedule>,
) : TenantScheduleRepository {
    val byTenant = rows.associateBy { it.tenantRef }.toMutableMap()

    override fun find(tenantRef: CommercialRef) = byTenant[tenantRef]

    override fun insert(
        schedule: TenantSchedule,
        setAt: Instant,
    ) {
        byTenant[schedule.tenantRef] = schedule
    }

    override fun update(schedule: TenantSchedule) {
        byTenant[schedule.tenantRef] = schedule
    }

    override fun dueForOpening(
        now: Instant,
        limit: Int,
    ) = byTenant.values
        .filter { it.state == ScheduleState.APPROVED && it.openedAt == null && !it.goLiveAt.isAfter(now) }
        .sortedWith(
            compareBy<TenantSchedule, Instant?>(nullsFirst()) { it.lastOpenAttemptAt }
                .thenBy { it.goLiveAt }
                .thenBy { it.scheduleRef },
        ).take(limit)
        .map { it.tenantRef }

    override fun recordOpenAttempt(
        tenantRef: CommercialRef,
        at: Instant,
    ) {
        byTenant[tenantRef] = byTenant.getValue(tenantRef).copy(lastOpenAttemptAt = at)
    }

    override fun lock(tenantRef: CommercialRef) = find(tenantRef)
}
