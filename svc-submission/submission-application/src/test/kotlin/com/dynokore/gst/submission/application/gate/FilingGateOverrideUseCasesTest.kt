package com.dynokore.gst.submission.application.gate

import com.dynokore.gst.submission.domain.gate.CheckVerdict
import com.dynokore.gst.submission.domain.gate.GateMode
import com.dynokore.gst.submission.domain.gate.LatestCheck
import com.dynokore.gst.submission.domain.gate.OverrideStatus
import com.dynokore.gst.submission.domain.gate.REFUSAL_CODE
import com.dynokore.gst.submission.domain.gate.ReleaseOutcome
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FilingGateOverrideUseCasesTest {
    private val entity = "e1"
    private val period = "2026-Q3"
    private val c1 = LatestCheck("c1", CheckVerdict.NOT_READY)

    private class World(
        mode: GateMode = GateMode.BLOCKING,
        latest: LatestCheck?,
        holders: Set<String> = setOf("A1", "A2", "A3"),
        decisions: FakeDecisions = FakeDecisions(),
        rendezvous: CyclicBarrier? = null,
    ) {
        val checks = FakeChecks(latest)
        val authority = FakeAuthority(holders)
        val decisions = decisions
        val store = FakeStore(rendezvous)
        val audit = FakeAudit()
        val request = RequestOverride(authority, FakeModes(mode), checks, decisions, store, audit)
        val approve = ApproveOverride(authority, checks, decisions, store, audit)
        val read = ReadOverrides(authority, checks, store)
    }

    private fun world(
        mode: GateMode = GateMode.BLOCKING,
        latest: LatestCheck? = c1,
        decisions: FakeDecisions = FakeDecisions(),
    ) = World(mode, latest, decisions = decisions)

    private fun World.ask(
        user: String = "A1",
        check: String = "c1",
        reason: String? = "because",
    ) = request.execute(user, entity, period, check, reason)

    @Test
    fun `request with a missing, empty or blank reason is BadRequest and the store is not called`() {
        for (reason in listOf(null, "", "   ")) {
            val w = world()
            w.ask(reason = reason) shouldBe OverrideResult.BadRequest
            w.store.insertCalls shouldBe 0
            w.audit.size shouldBe 0
        }
    }

    @Test
    fun `request in ADVISORY mode, for an unknown check or for a READY check is NotFound with nothing stored`() {
        val advisory = world(mode = GateMode.ADVISORY)
        advisory.ask() shouldBe OverrideResult.NotFound
        advisory.store.insertCalls shouldBe 0
        val unknown = world()
        unknown.ask(check = "zz") shouldBe OverrideResult.NotFound
        unknown.store.insertCalls shouldBe 0
        val ready = world(latest = LatestCheck("c1", CheckVerdict.READY))
        ready.ask() shouldBe OverrideResult.NotFound
        ready.store.insertCalls shouldBe 0
    }

    @Test
    fun `request for a check that is no longer the latest is Conflict with nothing stored`() {
        val w = world()
        w.checks.latestCheck = LatestCheck("c2", CheckVerdict.NOT_READY)
        w.checks.known = setOf("c1", "c2")
        w.ask() shouldBe OverrideResult.Conflict
        w.store.insertCalls shouldBe 0
        w.audit.size shouldBe 0
    }

    @Test
    fun `request by a caller without the permission is Forbidden with nothing stored`() {
        val w = world()
        w.ask(user = "nobody") shouldBe OverrideResult.Forbidden
        w.store.insertCalls shouldBe 0
        w.audit.size shouldBe 0
        w.authority.asked.single() shouldBe Triple("nobody", "validation.override", entity)
    }

    @Test
    fun `request answered Released stores a RELEASED row and writes requested and released by the same user`() {
        val w = world(decisions = FakeDecisions(ReleaseOutcome.Released))
        val r = w.ask().shouldBeInstanceOf<OverrideResult.Released>()
        r.entry.status shouldBe OverrideStatus.RELEASED
        w.store.row(r.entry.override.id).status shouldBe OverrideStatus.RELEASED
        w.audit.requested shouldContainExactly listOf("A1" to "o1")
        w.audit.released shouldContainExactly listOf("A1" to "o1")
        w.decisions.calls.single() shouldBe Triple(REFUSAL_CODE, "A1", emptyList())
    }

    @Test
    fun `request answered AwaitsSecond stores a PENDING row and one requested entry`() {
        val w = world(decisions = FakeDecisions(ReleaseOutcome.AwaitsSecond))
        w.ask().shouldBeInstanceOf<OverrideResult.AwaitsSecond>()
        w.store.row("o1").status shouldBe OverrideStatus.PENDING
        w.audit.requested shouldContainExactly listOf("A1" to "o1")
        w.audit.released.size shouldBe 0
    }

    @Test
    fun `request answered Denied is Forbidden with nothing stored`() {
        val w = world(decisions = FakeDecisions(ReleaseOutcome.Denied("NOT_OVERRIDABLE")))
        w.ask() shouldBe OverrideResult.Forbidden
        w.store.insertCalls shouldBe 0
        w.audit.size shouldBe 0
    }

    @Test
    fun `a pending override whose check is no longer the latest reads as SUPERSEDED and approving it is Conflict`() {
        val w = world()
        w.ask().shouldBeInstanceOf<OverrideResult.AwaitsSecond>()
        w.checks.latestCheck = LatestCheck("c2", CheckVerdict.NOT_READY)
        w.read
            .execute("A2", entity, period)
            .shouldBeInstanceOf<OverrideResult.Listed>()
            .entries
            .single()
            .status shouldBe OverrideStatus.SUPERSEDED
        w.store.row("o1").status shouldBe OverrideStatus.PENDING
        w.approve.execute("A2", entity, period, "o1") shouldBe OverrideResult.Conflict
        w.store.row("o1").status shouldBe OverrideStatus.PENDING
    }

    @Test
    fun `approve with no pending row, an already released row or a row of another tenant is NotFound`() {
        val w = world()
        w.approve.execute("A2", entity, period, "o9") shouldBe OverrideResult.NotFound
        w.ask().shouldBeInstanceOf<OverrideResult.AwaitsSecond>()
        w.approve.execute("A2", "other-entity", period, "o1") shouldBe OverrideResult.NotFound
        w.approve.execute("A2", entity, period, "o1").shouldBeInstanceOf<OverrideResult.Released>()
        w.approve.execute("A3", entity, period, "o1") shouldBe OverrideResult.NotFound
    }

    @Test
    fun `approve by a caller without the permission or with a Denied decision is Forbidden`() {
        val w = world()
        w.ask().shouldBeInstanceOf<OverrideResult.AwaitsSecond>()
        w.approve.execute("nobody", entity, period, "o1") shouldBe OverrideResult.Forbidden
        w.approve.execute("A1", entity, period, "o1") shouldBe OverrideResult.Forbidden
        w.store.row("o1").status shouldBe OverrideStatus.PENDING
        w.audit.released.size shouldBe 0
    }

    @Test
    fun `approve whose update changes zero rows is NotFound`() {
        val w = world()
        w.ask().shouldBeInstanceOf<OverrideResult.AwaitsSecond>()
        val racing =
            object : OverrideStore by w.store {
                override fun release(
                    overrideId: String,
                    releasedBy: String,
                ): Boolean = false
            }
        val approve = ApproveOverride(w.authority, w.checks, w.decisions, racing, w.audit)
        approve.execute("A2", entity, period, "o1") shouldBe OverrideResult.NotFound
        w.audit.released.size shouldBe 0
    }

    @Test
    fun `read lists the entries of one period and a caller without the permission is Forbidden`() {
        val w = world()
        w.ask().shouldBeInstanceOf<OverrideResult.AwaitsSecond>()
        val listed = w.read.execute("A2", entity, period).shouldBeInstanceOf<OverrideResult.Listed>()
        listed.entries.map { it.status } shouldContainExactly listOf(OverrideStatus.PENDING)
        (w.read.execute("A2", entity, "other-period") as OverrideResult.Listed).entries.size shouldBe 0
        w.read.execute("nobody", entity, period) shouldBe OverrideResult.Forbidden
    }

    @Test
    fun `INV-AC-17049 second approver waits, releases once and audits the request and the release`() {
        val w = World(latest = c1, rendezvous = CyclicBarrier(2))
        val noRendezvous = world()
        // A1 requests; A1 approves again; A2 approves (the single-thread part runs on a store without the barrier).
        noRendezvous.ask("A1").shouldBeInstanceOf<OverrideResult.AwaitsSecond>()
        noRendezvous.store.rowCount shouldBe 1
        noRendezvous.store.row("o1").status shouldBe OverrideStatus.PENDING
        noRendezvous.approve.execute("A1", entity, period, "o1") shouldBe OverrideResult.Forbidden
        noRendezvous.store.row("o1").status shouldBe OverrideStatus.PENDING
        noRendezvous.approve.execute("A2", entity, period, "o1").shouldBeInstanceOf<OverrideResult.Released>()
        noRendezvous.audit.requested shouldContainExactly listOf("A1" to "o1")
        noRendezvous.audit.released shouldContainExactly listOf("A2" to "o1")

        // A2 and A3 approve a second pending override at the same moment (two threads against the fake store).
        w.ask("A1").shouldBeInstanceOf<OverrideResult.AwaitsSecond>()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results =
                listOf("A2", "A3")
                    .map { u -> pool.submit<OverrideResult> { w.approve.execute(u, entity, period, "o1") } }
                    .map { it.get(10, TimeUnit.SECONDS) }
            results.count { it is OverrideResult.Released } shouldBe 1
            results.count { it == OverrideResult.NotFound } shouldBe 1
        } finally {
            pool.shutdownNow()
        }
        w.audit.released.size shouldBe 1
        w.audit.released
            .single()
            .second shouldBe "o1"

        // Empty reason, stale check and no permission: no new row and no new audit entry.
        val rows = noRendezvous.store.rowCount
        val audits = noRendezvous.audit.size
        noRendezvous.ask("A1", reason = "") shouldBe OverrideResult.BadRequest
        noRendezvous.checks.latestCheck = LatestCheck("c2", CheckVerdict.NOT_READY)
        noRendezvous.checks.known = setOf("c1", "c2")
        noRendezvous.ask("A1") shouldBe OverrideResult.Conflict
        noRendezvous.ask("nobody") shouldBe OverrideResult.Forbidden
        noRendezvous.store.rowCount shouldBe rows
        noRendezvous.audit.size shouldBe audits
    }

    @Test
    fun `source scan finds no role name as a string literal in the production code`() {
        val roots =
            listOf("src/main", "../submission-domain/src/main").map(::File).filter { it.isDirectory }
        (roots.size) shouldBe 2
        val offenders =
            roots.flatMap { root ->
                root
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .filter {
                        roleLiterals(
                            it.readText(),
                        ).isNotEmpty()
                    }.map { it.path }
                    .toList()
            }
        offenders shouldBe emptyList()
    }

    @Test
    fun `source scan fails a copy that adds a role name literal`() {
        for (role in ROLES) {
            roleLiterals("val x = \"$role\"") shouldBe listOf(role)
            roleLiterals("check(role == \"$role\")") shouldBe listOf(role)
        }
        roleLiterals("val x = \"tax.approver2\"") shouldBe emptyList()
    }

    private companion object {
        val ROLES = listOf("tax.approver", "tenant.admin", "agent.operator", "entity.admin")

        fun roleLiterals(source: String): List<String> =
            Regex(
                "\"(${ROLES.joinToString("|") { Regex.escape(it) }})\"",
            ).findAll(source).map { it.groupValues[1] }.toList()
    }
}
