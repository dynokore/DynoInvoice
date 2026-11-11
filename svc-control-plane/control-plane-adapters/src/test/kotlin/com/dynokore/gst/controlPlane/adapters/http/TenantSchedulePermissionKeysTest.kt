package com.dynokore.gst.controlPlane.adapters.http

import com.dynokore.gst.controlPlane.application.pack.RequestContext
import com.dynokore.gst.controlPlane.application.release.OperatorPermissions
import com.dynokore.gst.controlPlane.application.schedule.ApproveTenantSchedule
import com.dynokore.gst.controlPlane.application.schedule.ApproveTenantScheduleCommand
import com.dynokore.gst.controlPlane.application.schedule.CancelTenantSchedule
import com.dynokore.gst.controlPlane.application.schedule.CancelTenantScheduleCommand
import com.dynokore.gst.controlPlane.application.schedule.ChangeTenantSchedule
import com.dynokore.gst.controlPlane.application.schedule.ChangeTenantScheduleCommand
import com.dynokore.gst.controlPlane.application.schedule.GetTenantSchedule
import com.dynokore.gst.controlPlane.application.schedule.SchedulePermission
import com.dynokore.gst.controlPlane.application.schedule.SetTenantSchedule
import com.dynokore.gst.controlPlane.application.schedule.SetTenantScheduleCommand
import com.dynokore.gst.controlPlane.application.schedule.TenantScheduleRepository
import com.dynokore.gst.controlPlane.domain.commercial.CommercialRef
import com.dynokore.gst.controlPlane.domain.pack.ActorId
import com.dynokore.gst.controlPlane.domain.schedule.CustodyHolder
import com.dynokore.gst.controlPlane.domain.schedule.TenantSchedule
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Unit proof of INV-AC-19761: each of the five tenant schedule operations is decided by its own permission key through
 * the [OperatorPermissions] port, never by a role name (INV-Q-10027, INV-Q-10127 A).
 */
class TenantSchedulePermissionKeysTest {
    private val actor = "operator-1"
    private val tenant = CommercialRef("tenant-1")
    private val request = RequestContext("corr-1", "cause-1")
    private val at = Instant.parse("2030-01-01T00:00:00Z")

    private val keys =
        listOf(
            SchedulePermission.SET,
            SchedulePermission.CHANGE,
            SchedulePermission.CANCEL,
            SchedulePermission.READ,
            SchedulePermission.APPROVE,
        )

    /** Records every call; allows a call only when its key is in [allowed] (null allows nothing). */
    private class RecordingPermissions(
        private val allowed: Set<String>,
    ) : OperatorPermissions {
        val calls = mutableListOf<Pair<String, String>>()

        override fun holds(
            actor: String,
            permissionKey: String,
        ): Boolean {
            calls += actor to permissionKey
            return permissionKey in allowed
        }
    }

    /** Every method fails the test: a permission decision must not touch the repository. */
    private val repository =
        object : TenantScheduleRepository {
            override fun find(tenantRef: CommercialRef): TenantSchedule? = fail("find called")

            override fun insert(
                schedule: TenantSchedule,
                setAt: Instant,
            ) = fail<Unit>("insert called")

            override fun update(schedule: TenantSchedule) = fail<Unit>("update called")

            override fun dueForOpening(
                now: Instant,
                limit: Int,
            ): List<CommercialRef> = fail("dueForOpening called")

            override fun recordOpenAttempt(
                tenantRef: CommercialRef,
                at: Instant,
            ) = fail<Unit>("recordOpenAttempt called")

            override fun lock(tenantRef: CommercialRef): TenantSchedule? = fail("lock called")
        }

    /** The five operations in the order of [keys]; each takes the port and decides the actor of its command. */
    private fun operations(commandActor: String): List<(OperatorPermissions) -> Boolean> {
        val clock = Clock.fixed(at, ZoneOffset.UTC)
        val who = ActorId(commandActor)
        val holder = CustodyHolder("Holder", "holder@example.com", null)
        val set = SetTenantScheduleCommand(who, tenant, "C-1", at, at.plusSeconds(60), holder, null, request)
        val change = ChangeTenantScheduleCommand(who, tenant, "S-1", null, null, request)
        val approve = ApproveTenantScheduleCommand(who, tenant, ApproveTenantScheduleCommand.Target.SCHEDULE, request)
        return listOf(
            { p -> SetTenantSchedule(repository, p, clock) { "ref" }.permits(actor, set) },
            { p -> ChangeTenantSchedule(repository, p, clock).permits(actor, change) },
            { p ->
                CancelTenantSchedule(repository, p).permits(actor, CancelTenantScheduleCommand(who, tenant, request))
            },
            { p -> GetTenantSchedule(repository, p).permits(actor) },
            { p -> ApproveTenantSchedule(repository, p, clock).permits(actor, approve) },
        )
    }

    private fun decisions(
        permissions: OperatorPermissions,
        commandActor: String = actor,
    ): List<Boolean> = operations(commandActor).map { it(permissions) }

    @Test
    @DisplayName("INV-AC-19761 each of the five operations asks the permission port for its own key and no other")
    fun `INV-AC-19761 each of the five operations asks the permission port for its own key and no other`() {
        SchedulePermission.SET shouldBe "tenant-schedule.set"
        SchedulePermission.CHANGE shouldBe "tenant-schedule.change"
        SchedulePermission.CANCEL shouldBe "tenant-schedule.cancel"
        SchedulePermission.READ shouldBe "tenant-schedule.read"
        SchedulePermission.APPROVE shouldBe "tenant-schedule.approve"
        val expected =
            listOf(
                "tenant-schedule.set",
                "tenant-schedule.change",
                "tenant-schedule.cancel",
                "tenant-schedule.read",
                "tenant-schedule.approve",
            )
        val recorded =
            operations(actor).map { operation ->
                val stub = RecordingPermissions(emptySet())
                operation(stub)
                stub.calls.size shouldBe 1
                stub.calls.single().first shouldBe actor
                stub.calls.single().second
            }
        recorded shouldBe expected
        recorded shouldBe keys
        recorded.toSet().size shouldBe 5
    }

    @Test
    @DisplayName("INV-AC-19761 an actor allowed only one of the other four keys is refused by each operation")
    fun `INV-AC-19761 an actor allowed only one of the other four keys is refused by each operation`() {
        keys.forEachIndexed { operation, _ ->
            keys.forEachIndexed { other, key ->
                if (other != operation) {
                    decisions(RecordingPermissions(setOf(key)))[operation] shouldBe false
                }
            }
        }
    }

    @Test
    @DisplayName("INV-AC-19761 the look-alike keys tenant-schedule.sets and Tenant-schedule.set allow no operation")
    fun `INV-AC-19761 the look-alike keys tenant-schedule_sets and Tenant-schedule_set allow no operation`() {
        val lookAlikes =
            keys.flatMap { listOf(it + "s", it.replaceFirstChar(Char::uppercaseChar), "commercial.admin") } +
                listOf("tenant-schedule.sets", "Tenant-schedule.set")
        lookAlikes.forEach { decisions(RecordingPermissions(setOf(it))) shouldBe List(5) { false } }
    }

    @Test
    @DisplayName(
        "INV-AC-19761 an actor allowed the key of an operation is allowed and an actor allowed nothing is refused",
    )
    fun `INV-AC-19761 an actor allowed the key of an operation is allowed and an actor allowed nothing is refused`() {
        keys.forEachIndexed { operation, key ->
            val expected = List(5) { it == operation }
            decisions(RecordingPermissions(setOf(key))) shouldBe expected
            // the command names another actor than the caller: refused though the key is held
            if (operation != 3) decisions(RecordingPermissions(setOf(key)), "someone-else")[operation] shouldBe false
        }
        decisions(RecordingPermissions(emptySet())) shouldBe List(5) { false }
    }
}
