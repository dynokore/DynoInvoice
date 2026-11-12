package com.dynokore.gst.submission.application.filing

import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.OperationRoles
import com.dynokore.gst.submission.application.gate.GateModes
import com.dynokore.gst.submission.application.gate.LatestChecks
import com.dynokore.gst.submission.application.readiness.ReleasedOverrideChecks
import com.dynokore.gst.submission.domain.filing.FilingConflict
import com.dynokore.gst.submission.domain.gate.CheckVerdict
import com.dynokore.gst.submission.domain.gate.GateMode
import com.dynokore.gst.submission.domain.gate.LatestCheck
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.CellAllowance
import com.dynokore.platform.serviceruntime.replica.DecisionReason
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.MachineEntityCoverage
import com.dynokore.platform.serviceruntime.replica.MachinePrincipalFixtures
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class ConfirmFilingTest {
    private val clock = Clock.fixed(Instant.parse("2026-04-10T00:00:00Z"), ZoneOffset.UTC)
    private val deadline = Deadline.after(Duration.ofSeconds(5), clock)
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
    private val u1 = Principal("u1", PrincipalKind.HUMAN)
    private val u2 = Principal("u2", PrincipalKind.HUMAN)
    private val due = LocalDate.of(2026, 4, 30)

    private val roleMap =
        OperationRoleMap { key ->
            OperationRoles.rolesFor(DeploymentForm.SAAS, key).mapValues { CellAllowance.valueOf(it.value.name) }
        }

    /** A role the catalogue lists for [key]; no role name is written in this test. */
    private fun roleOf(key: String): String =
        roleMap
            .rolesFor(key)
            .keys
            .sorted()
            .first()

    private fun grant(
        replica: AuthorityReplica,
        principalId: String,
        key: String,
        entityRef: String,
        version: Long = 1,
    ) {
        val role = roleOf(key)
        val g = RoleGrant(role, GrantScope.ENTITY, entityRef, clock.instant().minusSeconds(60))
        val record = AuthorityRecord.RoleGrantRecord("$role-$principalId-$key", RoleGrantState.ACTIVE, g)
        replica.accept(AuthorityChanged(tenant, principalId, version, record))
    }

    private fun replicaWith(vararg grants: Triple<String, String, String>): AuthorityReplica =
        AuthorityReplica(clock, roleMap).also { r ->
            r.onSnapshotComplete(0)
            grants.forEachIndexed { i, (who, key, entity) -> grant(r, who, key, entity, (i + 1).toLong()) }
        }

    private fun machine(
        id: String = "m1",
        coverage: MachineEntityCoverage = MachineEntityCoverage.Entity("e1"),
        type: String = "filing-workflow",
    ): Principal = MachinePrincipalFixtures.machine(id, tenant, coverage, type)

    /** Stamps a growing `recordedAt` per fact; identical facts are appended once. */
    private class FakeStore(
        private val log: MutableList<String> = mutableListOf(),
    ) : FilingFactStore {
        val handed = mutableListOf<NewFilingFact>()
        private val stored = mutableListOf<FilingFact>()
        var stamp: Instant = Instant.parse("2026-04-18T08:00:00Z")

        override fun facts(
            entityRef: String,
            periodRef: String,
        ): List<FilingFact> = stored.filter { it.entityRef == entityRef && it.periodRef == periodRef }

        override fun append(fact: NewFilingFact) {
            log += "append"
            handed += fact
            val same =
                stored.any {
                    it.entityRef == fact.entityRef && it.periodRef == fact.periodRef &&
                        it.filedAt == fact.filedAt && it.acknowledgementRef == fact.acknowledgementRef &&
                        it.correction == fact.correction
                }
            if (!same) {
                stored +=
                    FilingFact(
                        fact.entityRef,
                        fact.periodRef,
                        fact.filedAt,
                        fact.acknowledgementRef,
                        fact.portalRef,
                        fact.correction,
                        fact.recordedBy,
                        stamp,
                    )
            }
        }
    }

    private val log = mutableListOf<String>()
    private val store = FakeStore(log)

    private val dueDates =
        FilingDueDates { _, period ->
            log += "due"
            when (period) {
                "P2" -> null
                "P3" -> throw AmbiguousDueDateException("two dates")
                else -> due
            }
        }
    private val checks =
        mapOf(
            "P" to LatestCheck("chk-7", CheckVerdict.READY),
            "P4" to LatestCheck("chk-8", CheckVerdict.NOT_READY),
            "P5" to LatestCheck("chk-9", CheckVerdict.NOT_READY),
        )
    private val latest =
        object : LatestChecks {
            override fun latest(
                entityRef: String,
                periodRef: String,
            ): LatestCheck? {
                log += "gate"
                return checks[periodRef] ?: LatestCheck("chk-7", CheckVerdict.READY)
            }

            override fun knownCheckIds(
                entityRef: String,
                periodRef: String,
            ): Set<String> = emptySet()
        }
    private val released = ReleasedOverrideChecks { _, period -> if (period == "P5") setOf("chk-9") else emptySet() }
    private val modes = GateModes { GateMode.BLOCKING }

    private fun useCase(replica: AuthorityReplica) = ConfirmFiling(replica, dueDates, modes, latest, released, store)

    private fun holder() = replicaWith(Triple(u1.id, ConfirmFilingActions.PERSON, "e1"))

    private fun req(
        who: Principal = u1,
        period: String = "P",
        day: Int = 18,
        ack: String = "A1",
        correction: Boolean = false,
        portal: String? = null,
    ) = ConfirmFilingRequest(tenant, who, "e1", period, LocalDate.of(2026, 4, day), ack, portal, correction)

    @Test
    fun `INV-AC-20622 first fact second date and correction`() {
        val uc = useCase(holder())
        val first = uc.handle(req(), deadline).shouldBeInstanceOf<ConfirmFilingResult.Recorded>()
        first.governingFiledAt shouldBe LocalDate.of(2026, 4, 18)
        first.conflict shouldBe null
        store.handed.size shouldBe 1
        store.handed[0].filedAt shouldBe LocalDate.of(2026, 4, 18)
        store.handed[0].recordedBy shouldBe "u1"

        val second = uc.handle(req(day = 20, ack = "A2"), deadline).shouldBeInstanceOf<ConfirmFilingResult.Recorded>()
        second.governingFiledAt shouldBe LocalDate.of(2026, 4, 18)
        second.conflict shouldBe FilingConflict(LocalDate.of(2026, 4, 20))
        second.conflict!!.code shouldBe "PER-010"

        val fix =
            uc
                .handle(req(day = 19, ack = "A3", correction = true), deadline)
                .shouldBeInstanceOf<ConfirmFilingResult.Recorded>()
        fix.governingFiledAt shouldBe LocalDate.of(2026, 4, 19)
        fix.conflict shouldBe null
    }

    @Test
    fun `INV-AC-20622 U2 is refused and nothing is handed to the store`() {
        useCase(holder()).handle(req(who = u2), deadline) shouldBe
            ConfirmFilingResult.Forbidden(DecisionReason.NO_GRANT)
        store.handed shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20622 a period without a due date is refused and nothing is handed`() {
        useCase(holder()).handle(req(period = "P2"), deadline) shouldBe ConfirmFilingResult.NoDueDate
        store.handed shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20622 a period with more than one due date is refused and nothing is handed`() {
        useCase(holder()).handle(req(period = "P3"), deadline) shouldBe ConfirmFilingResult.NoDueDate
        store.handed shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20622 a NOT_READY period without a released override is refused with PER-001 and nothing is handed`() {
        useCase(holder()).handle(req(period = "P4"), deadline) shouldBe ConfirmFilingResult.GateRefused("PER-001")
        store.handed shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20622 a released override proceeds and one fact is handed`() {
        useCase(holder()).handle(req(period = "P5"), deadline).shouldBeInstanceOf<ConfirmFilingResult.Recorded>()
        store.handed.size shouldBe 1
    }

    @Test
    fun `recordedBy is the principal id for a HUMAN and for a MACHINE`() {
        val m = machine()
        val r = replicaWith(Triple(u1.id, ConfirmFilingActions.PERSON, "e1"))
        useCase(r).handle(req(), deadline)
        useCase(r).handle(req(who = m, day = 19, ack = "A2"), deadline)
        store.handed.map { it.recordedBy } shouldBe listOf("u1", "m1")
    }

    @Test
    fun `a HUMAN holder is allowed and a HUMAN stranger is Forbidden with nothing handed`() {
        val uc = useCase(holder())
        uc.handle(req(), deadline).shouldBeInstanceOf<ConfirmFilingResult.Recorded>()
        store.handed.clear()
        uc.handle(req(who = u2), deadline).shouldBeInstanceOf<ConfirmFilingResult.Forbidden>()
        store.handed shouldBe emptyList()
    }

    @Test
    fun `a MACHINE covering the entity is allowed through the machine key`() {
        useCase(replicaWith())
            .handle(req(who = machine()), deadline)
            .shouldBeInstanceOf<ConfirmFilingResult.Recorded>()
        store.handed.size shouldBe 1
    }

    @Test
    fun `a MACHINE covering another entity is Forbidden with nothing handed`() {
        useCase(replicaWith())
            .handle(req(who = machine(coverage = MachineEntityCoverage.Entity("e2"))), deadline)
            .shouldBeInstanceOf<ConfirmFilingResult.Forbidden>()
        store.handed shouldBe emptyList()
    }

    @Test
    fun `a MACHINE is not allowed by a grant of the person key alone`() {
        val m = machine(type = "no-such-type")
        val r = replicaWith(Triple(m.id, ConfirmFilingActions.PERSON, "e1"))
        useCase(r).handle(req(who = m), deadline).shouldBeInstanceOf<ConfirmFilingResult.Forbidden>()
        store.handed shouldBe emptyList()
    }

    @Test
    fun `the order of checks is permission then due date then gate then append`() {
        useCase(holder()).handle(req(), deadline)
        log shouldBe listOf("due", "gate", "append")
        log.clear()
        useCase(holder()).handle(req(who = u2), deadline)
        log shouldBe emptyList()
        useCase(holder()).handle(req(period = "P2"), deadline)
        log shouldBe listOf("due")
    }

    @Test
    fun `confirmedAt is the recordedAt of the equal stored fact and a repeat answers the same value`() {
        val uc = useCase(holder())
        val first = uc.handle(req(), deadline).shouldBeInstanceOf<ConfirmFilingResult.Recorded>()
        first.confirmedAt shouldBe Instant.parse("2026-04-18T08:00:00Z")
        store.stamp = Instant.parse("2026-04-19T09:00:00Z")
        val repeat = uc.handle(req(), deadline).shouldBeInstanceOf<ConfirmFilingResult.Recorded>()
        repeat.confirmedAt shouldBe first.confirmedAt
        val second = uc.handle(req(day = 20, ack = "A2"), deadline).shouldBeInstanceOf<ConfirmFilingResult.Recorded>()
        second.confirmedAt shouldBe Instant.parse("2026-04-19T09:00:00Z")
    }

    @Test
    fun `the handed fact carries portalRef as given and null when absent`() {
        val uc = useCase(holder())
        uc.handle(req(portal = "portal-1"), deadline)
        uc.handle(req(day = 20, ack = "A2"), deadline)
        store.handed.map { it.portalRef } shouldBe listOf("portal-1", null)
    }
}
