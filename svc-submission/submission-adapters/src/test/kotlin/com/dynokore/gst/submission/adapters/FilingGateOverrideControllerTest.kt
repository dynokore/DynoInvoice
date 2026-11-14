package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.http.ComplianceRefusal
import com.dynokore.gst.submission.adapters.http.FilingGateOverrides
import com.dynokore.gst.submission.adapters.http.ReadinessController
import com.dynokore.gst.submission.adapters.http.ReadinessRefusalAdvice
import com.dynokore.gst.submission.adapters.persistence.OverrideTimeSource
import com.dynokore.gst.submission.adapters.persistence.OverrideTimes
import com.dynokore.gst.submission.application.gate.ApproveOverride
import com.dynokore.gst.submission.application.gate.AuditTrail
import com.dynokore.gst.submission.application.gate.GateModes
import com.dynokore.gst.submission.application.gate.LatestChecks
import com.dynokore.gst.submission.application.gate.NewOverride
import com.dynokore.gst.submission.application.gate.OverrideAuthority
import com.dynokore.gst.submission.application.gate.OverrideStore
import com.dynokore.gst.submission.application.gate.ReadOverrides
import com.dynokore.gst.submission.application.gate.ReleaseDecisions
import com.dynokore.gst.submission.application.gate.RequestOverride
import com.dynokore.gst.submission.application.gate.StoredOverride
import com.dynokore.gst.submission.application.gate.VALIDATION_OVERRIDE
import com.dynokore.gst.submission.application.readiness.GetLatestReadinessCheck
import com.dynokore.gst.submission.contract.model.FilingGateOverride
import com.dynokore.gst.submission.contract.model.FilingGateOverrideEntry
import com.dynokore.gst.submission.contract.model.FilingGateOverrideRequest
import com.dynokore.gst.submission.contract.model.PendingFilingGateOverride
import com.dynokore.gst.submission.domain.gate.CheckVerdict
import com.dynokore.gst.submission.domain.gate.FilingGate
import com.dynokore.gst.submission.domain.gate.GateDecision
import com.dynokore.gst.submission.domain.gate.GateMode
import com.dynokore.gst.submission.domain.gate.LatestCheck
import com.dynokore.gst.submission.domain.gate.OverrideStatus
import com.dynokore.gst.submission.domain.gate.Proceed
import com.dynokore.gst.submission.domain.gate.ProceedBasis
import com.dynokore.gst.submission.domain.gate.REFUSAL_CODE
import com.dynokore.gst.submission.domain.gate.Refuse
import com.dynokore.gst.submission.domain.gate.ReleaseOutcome
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalBindingFilter
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import jakarta.servlet.http.HttpServletRequest
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.postgresql.util.PSQLException
import org.postgresql.util.ServerErrorMessage
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.http.ResponseEntity
import org.springframework.mock.web.MockHttpServletRequest
import java.time.Instant
import java.time.ZoneId
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors

/** The failure of the insert on the unique index of the repeat key, as jOOQ wraps the driver's report. */
private fun repeatKeyViolation(index: String = "filing_override_one_per_check") =
    DataAccessException(
        "unique violation",
        PSQLException(ServerErrorMessage("SERROR\u0000C23505\u0000n$index\u0000")),
    )

/** The three operations at the controller with fakes for the ports (INV-T-10714, INV-Q-10691 A). */
class FilingGateOverrideControllerTest {
    private class FakeStore :
        OverrideStore,
        OverrideTimeSource {
        val rows = mutableListOf<StoredOverride>()
        var inserts = 0
        var duplicateIndex = "filing_override_one_per_check"

        override fun insert(override: NewOverride): StoredOverride {
            val same = { r: StoredOverride ->
                r.entityRef == override.entityRef && r.periodRef == override.periodRef && r.checkId == override.checkId
            }
            if (rows.any(same)) {
                throw repeatKeyViolation(duplicateIndex)
            }
            inserts++
            val row =
                StoredOverride(
                    "id-${rows.size + 1}",
                    override.entityRef,
                    override.periodRef,
                    override.checkId,
                    override.reason,
                    override.status,
                    override.requestedBy,
                    override.releasedBy,
                )
            rows += row
            return row
        }

        override fun findPending(
            entityRef: String,
            periodRef: String,
            overrideId: String,
        ) = rows.firstOrNull {
            it.id == overrideId && it.entityRef == entityRef && it.periodRef == periodRef &&
                it.status == OverrideStatus.PENDING
        }

        @Synchronized
        override fun release(
            overrideId: String,
            releasedBy: String,
        ): Boolean {
            val i = rows.indexOfFirst { it.id == overrideId && it.status == OverrideStatus.PENDING }
            if (i < 0) return false
            rows[i] = rows[i].copy(status = OverrideStatus.RELEASED, releasedBy = releasedBy)
            return true
        }

        override fun entries(
            entityRef: String,
            periodRef: String,
        ) = rows.filter { it.entityRef == entityRef && it.periodRef == periodRef }

        var times: Map<String, OverrideTimes>? = null

        override fun ofPeriod(
            entityRef: String,
            periodRef: String,
        ) = times ?: entries(entityRef, periodRef).associate {
            val releasedAt = Instant.parse("2026-11-10T09:00:00Z").takeIf { _ -> it.status == OverrideStatus.RELEASED }
            it.checkId to OverrideTimes(Instant.parse("2026-11-10T08:00:00Z"), releasedAt)
        }
    }

    private class FakeAudit : AuditTrail {
        val entries: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun requested(
            userId: String,
            override: StoredOverride,
        ) {
            entries += "REQUESTED:$userId:${override.reason}"
        }

        override fun approved(
            userId: String,
            override: StoredOverride,
        ) {
            entries += "APPROVED:$userId"
        }

        override fun released(
            userId: String,
            override: StoredOverride,
        ) {
            entries += "RELEASED:$userId:${override.requestedBy}"
        }
    }

    private class Checks : LatestChecks {
        var latest: LatestCheck? = LatestCheck("chk-2", CheckVerdict.NOT_READY)
        var known = setOf("chk-1", "chk-2")

        override fun latest(
            entityRef: String,
            periodRef: String,
        ) = latest

        override fun knownCheckIds(
            entityRef: String,
            periodRef: String,
        ) = known
    }

    private class World {
        val store = FakeStore()
        val audit = FakeAudit()
        val checks = Checks()
        var mode = GateMode.BLOCKING
        val holders = mutableSetOf("A1", "A2", "A3")

        /** What access-svc answers: a second approver is needed until a prior approver is named. */
        var decision: (List<String>) -> ReleaseOutcome = { prior ->
            if (prior.isEmpty()) ReleaseOutcome.AwaitsSecond else ReleaseOutcome.Released
        }
        val request = MockHttpServletRequest()

        private val authority =
            OverrideAuthority { user, permission, entity ->
                permission == VALIDATION_OVERRIDE && entity == "E1" && user in holders
            }
        private val decisions =
            ReleaseDecisions { _, code, approver, prior ->
                check(code == REFUSAL_CODE)
                check(approver.isNotBlank())
                decision(prior)
            }
        private val modes = GateModes { mode }
        private val runner =
            object : TransactionRunner {
                override fun <T> inTransaction(block: () -> T): T = block()
            }

        private fun controllerOf(req: MockHttpServletRequest): ReadinessController {
            val factory = DefaultListableBeanFactory().also { it.registerSingleton("request", req) }
            val provider = factory.getBeanProvider(HttpServletRequest::class.java)
            return ReadinessController(
                GetLatestReadinessCheck(unusedOf(), unusedOf()),
                provider,
                DrainFixture().preview(),
                DrainFixture().drain(),
                unusedConfirmFiling(),
                runner,
                ZoneId.of("Asia/Singapore"),
                FilingGateOverrides(
                    RequestOverride(authority, modes, checks, decisions, store, audit),
                    ApproveOverride(authority, checks, decisions, store, audit),
                    ReadOverrides(authority, checks, store),
                    store,
                    checks,
                    store,
                ),
            )
        }

        val controller = controllerOf(request)

        /** A controller bound to its own request of [user], for callers that act at the same moment. */
        fun controllerFor(user: String): ReadinessController {
            val req = MockHttpServletRequest()
            req.setAttribute(
                EndUserPrincipalBindingFilter.PRINCIPAL_ATTRIBUTE,
                EndUserPrincipal(user, TENANT, setOf(EntityRef("E1"))),
            )
            return controllerOf(req)
        }

        fun asUser(
            user: String,
            entities: Set<String> = setOf("E1"),
        ) {
            request.setAttribute(
                EndUserPrincipalBindingFilter.PRINCIPAL_ATTRIBUTE,
                EndUserPrincipal(user, TENANT, entities.map(::EntityRef).toSet()),
            )
        }

        fun override(
            user: String,
            check: String = "chk-2",
            reason: String = "supplier fix",
        ): ResponseEntity<FilingGateOverride> {
            asUser(user)
            return controller.overrideFilingGate("E1", "P1", FilingGateOverrideRequest(check, reason))
        }

        fun approve(
            user: String,
            check: String = "chk-2",
        ): ResponseEntity<FilingGateOverride> {
            asUser(user)
            return controller.approveFilingGateOverride("E1", "P1", check)
        }

        /** The filing call of the gate: refused with PER-001 unless READY or an override is released for the check. */
        fun filing(): GateDecision =
            FilingGate.decide(
                mode,
                checks.latest,
                store.entries("E1", "P1").firstOrNull { it.status == OverrideStatus.RELEASED }?.checkId,
            )
    }

    private fun refused(block: () -> Any?): ClosedErrorCode = assertThrows<ComplianceRefusal> { block() }.code

    private fun statusOf(code: ClosedErrorCode): Int {
        val response = ReadinessRefusalAdvice().refuse(ComplianceRefusal(code))
        response.body!!.contains(code.code) shouldBe true
        return response.statusCode.value()
    }

    private val proceeds = Proceed(ProceedBasis.OVERRIDDEN)

    private companion object {
        val TENANT = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
    }

    // ---- overrideFilingGate ---------------------------------------------------------------------------------------

    @Test
    fun `an empty reason is 400 SRT-001 with nothing stored`() {
        val w = World()
        refused { w.override("A1", reason = "") } shouldBe ClosedErrorCode.CONTRACT_VIOLATION
        refused { w.override("A1", reason = "   ") } shouldBe ClosedErrorCode.CONTRACT_VIOLATION
        statusOf(ClosedErrorCode.CONTRACT_VIOLATION) shouldBe 400
        w.store.rows shouldBe emptyList()
        w.audit.entries shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20023 ADVISORY mode is 404 SRT-005 with nothing stored`() {
        val w = World().also { it.mode = GateMode.ADVISORY }
        refused { w.override("A1") } shouldBe ClosedErrorCode.NOT_FOUND
        statusOf(ClosedErrorCode.NOT_FOUND) shouldBe 404
        w.store.rows shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20023 an unknown check is 404 SRT-005 with nothing stored`() {
        val w = World()
        refused { w.override("A1", check = "chk-9") } shouldBe ClosedErrorCode.NOT_FOUND
        w.store.rows shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20023 a READY check is 404 SRT-005 with nothing stored`() {
        val w = World().also { it.checks.latest = LatestCheck("chk-2", CheckVerdict.READY) }
        refused { w.override("A1") } shouldBe ClosedErrorCode.NOT_FOUND
        w.store.rows shouldBe emptyList()
    }

    @Test
    fun `a check that is no longer the latest is 409 SRT-008 with nothing stored`() {
        val w = World()
        refused { w.override("A1", check = "chk-1") } shouldBe ClosedErrorCode.STALE_AGGREGATE
        statusOf(ClosedErrorCode.STALE_AGGREGATE) shouldBe 409
        w.store.rows shouldBe emptyList()
        w.audit.entries shouldBe emptyList()
    }

    @Test
    fun `a caller without the permission is 403 SRT-004 with nothing stored`() {
        val w = World()
        refused { w.override("A9") } shouldBe ClosedErrorCode.ACCESS_DENIED
        statusOf(ClosedErrorCode.ACCESS_DENIED) shouldBe 403
        w.store.rows shouldBe emptyList()
        w.audit.entries shouldBe emptyList()
    }

    @Test
    fun `a caller whose token does not cover the entity is 404 SRT-005 and a call without a principal is 401`() {
        val w = World()
        w.asUser("A1", entities = setOf("E2"))
        val call = { w.controller.overrideFilingGate("E1", "P1", FilingGateOverrideRequest("chk-2", "r")) }
        refused(call) shouldBe ClosedErrorCode.NOT_FOUND
        w.request.removeAttribute(EndUserPrincipalBindingFilter.PRINCIPAL_ATTRIBUTE)
        refused(call) shouldBe ClosedErrorCode.UNAUTHENTICATED
        w.store.rows shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20023 the decision releasing is 201, the audit holds actor and reason, and the filing proceeds`() {
        val w = World().also { it.decision = { ReleaseOutcome.Released } }
        w.filing() shouldBe Refuse()
        val response = w.override("A1")
        response.statusCode.value() shouldBe 201
        val body = response.body!!
        body.checkId shouldBe "chk-2"
        body.verdict shouldBe FilingGateOverride.Verdict.NOT_READY
        body.reason shouldBe "supplier fix"
        body.overriddenBy shouldBe "A1"
        w.audit.entries shouldBe listOf("REQUESTED:A1:supplier fix", "RELEASED:A1:A1")
        w.filing() shouldBe proceeds
    }

    @Test
    fun `INV-AC-20023 in blocking mode the filing is refused unless an override is recorded for the latest check`() {
        val w = World().also { it.decision = { ReleaseOutcome.Released } }
        w.filing() shouldBe Refuse()
        w.override("A1")
        w.filing() shouldBe proceeds
        w.checks.latest = LatestCheck("chk-3", CheckVerdict.NOT_READY)
        w.checks.known = w.checks.known + "chk-3"
        w.filing() shouldBe Refuse()
    }

    @Test
    fun `INV-AC-20023 a second approver awaited is 202 with a PENDING row and the filing stays refused`() {
        val w = World()
        val response = w.override("A1")
        response.statusCode.value() shouldBe 202
        val body = response.body as Any
        (body as PendingFilingGateOverride).status shouldBe PendingFilingGateOverride.Status.PENDING
        body.requestedBy shouldBe "A1"
        w.store.rows
            .single()
            .status shouldBe OverrideStatus.PENDING
        w.filing() shouldBe Refuse()
        w.audit.entries shouldBe listOf("REQUESTED:A1:supplier fix")
    }

    @Test
    fun `INV-AC-20023 a repeat of the key reports the recorded override unchanged and writes no second entry`() {
        val w = World()
        w.override("A1").statusCode.value() shouldBe 202
        w.override("A1").statusCode.value() shouldBe 202
        w.store.inserts shouldBe 1
        w.audit.entries.size shouldBe 1
        w.approve("A2").statusCode.value() shouldBe 201
        w.override("A1").statusCode.value() shouldBe 201
        w.audit.entries.size shouldBe 2
    }

    @Test
    fun `INV-AC-20023 a unique violation of another index propagates and is not read as a repeat`() {
        val w = World()
        w.override("A1").statusCode.value() shouldBe 202
        w.store.duplicateIndex = "filing_override_one_per_check2"
        assertThrows<DataAccessException> { w.override("A1") }
        w.store.duplicateIndex = "Filing_Override_One_Per_Check"
        assertThrows<DataAccessException> { w.override("A1") }
        w.store.rows.size shouldBe 1
    }

    @Test
    fun `INV-AC-20023 a stored override without its audit instant fails the read and invents none`() {
        val w = World()
        w.override("A1").statusCode.value() shouldBe 202
        w.store.times = emptyMap()
        assertThrows<IllegalStateException> { w.controller.getFilingGateOverrides("E1", "P1", 10, null) }
        w.store.times = null
        w.approve("A2").statusCode.value() shouldBe 201
        w.store.times = mapOf("chk-2" to OverrideTimes(Instant.parse("2026-11-10T08:00:00Z"), null))
        assertThrows<IllegalStateException> { w.controller.getFilingGateOverrides("E1", "P1", 10, null) }
    }

    // ---- INV-AC-20023 ---------------------------------------------------------------------------------------------

    @Test
    fun `INV-AC-20023 A1 requests and waits, A1 approving again is 403, A2 approves and the filing proceeds`() {
        val w = World()
        // access-svc: the same person twice is a DENY; a different person releases.
        w.decision = { prior -> if (prior.isEmpty()) ReleaseOutcome.AwaitsSecond else ReleaseOutcome.Released }
        w.override("A1").statusCode.value() shouldBe 202
        w.filing() shouldBe Refuse(REFUSAL_CODE)

        w.decision = { prior ->
            if ("A1" in prior) ReleaseOutcome.Denied("SAME_PERSON_TWICE") else ReleaseOutcome.Released
        }
        val again = refused { w.approve("A1") }
        again shouldBe ClosedErrorCode.ACCESS_DENIED
        w.filing() shouldBe Refuse(REFUSAL_CODE)
        w.audit.entries shouldBe listOf("REQUESTED:A1:supplier fix")

        w.decision = { _ -> ReleaseOutcome.Released }
        val released = w.approve("A2")
        released.statusCode.value() shouldBe 201
        released.body!!.overriddenBy shouldBe "A2"
        w.filing() shouldBe proceeds
        w.audit.entries shouldBe listOf("REQUESTED:A1:supplier fix", "RELEASED:A2:A1")
    }

    @Test
    fun `INV-AC-20023 two approvers at once release once, one 201 and one 404 with one release entry`() {
        val w = World()
        w.override("A1").statusCode.value() shouldBe 202
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results =
                listOf("A2", "A3")
                    .map { user ->
                        pool.submit<Int> {
                            val c = w.controllerFor(user)
                            barrier.await()
                            try {
                                c.approveFilingGateOverride("E1", "P1", "chk-2").statusCode.value()
                            } catch (e: ComplianceRefusal) {
                                if (e.code == ClosedErrorCode.NOT_FOUND) 404 else -1
                            }
                        }
                    }.map { it.get() }
            results.sorted() shouldBe listOf(201, 404)
        } finally {
            pool.shutdownNow()
        }
        w.store.rows
            .single()
            .status shouldBe OverrideStatus.RELEASED
        w.audit.entries.count { it.startsWith("RELEASED") } shouldBe 1
        w.filing() shouldBe proceeds
    }

    // ---- approveFilingGateOverride --------------------------------------------------------------------------------

    @Test
    fun `approve with no pending row or an already released row is 404 SRT-005`() {
        val w = World()
        refused { w.approve("A2") } shouldBe ClosedErrorCode.NOT_FOUND
        w.override("A1")
        w.approve("A2").statusCode.value() shouldBe 201
        refused { w.approve("A3") } shouldBe ClosedErrorCode.NOT_FOUND
        w.audit.entries.count { it.startsWith("RELEASED") } shouldBe 1
    }

    @Test
    fun `approve by a caller without the permission or answered DENY is 403 SRT-004`() {
        val w = World()
        w.override("A1")
        refused { w.approve("A9") } shouldBe ClosedErrorCode.ACCESS_DENIED
        w.decision = { ReleaseOutcome.Denied("NOT_OVERRIDABLE") }
        refused { w.approve("A2") } shouldBe ClosedErrorCode.ACCESS_DENIED
        w.store.rows
            .single()
            .status shouldBe OverrideStatus.PENDING
    }

    @Test
    fun `approve of an unknown check is 403 for a caller without the permission and 404 for a holder`() {
        val w = World()
        refused { w.approve("A9", check = "chk-9") } shouldBe ClosedErrorCode.ACCESS_DENIED
        refused { w.approve("A2", check = "chk-9") } shouldBe ClosedErrorCode.NOT_FOUND
    }

    @Test
    fun `a pending override whose check is no longer the latest reads as SUPERSEDED and approving it is 409 SRT-008`() {
        val w = World()
        w.override("A1")
        w.checks.latest = LatestCheck("chk-3", CheckVerdict.NOT_READY)
        refused { w.approve("A2") } shouldBe ClosedErrorCode.STALE_AGGREGATE
        w.store.rows
            .single()
            .status shouldBe OverrideStatus.PENDING
        w.asUser("A2")
        val listed = w.controller.getFilingGateOverrides("E1", "P1", 100, null).body!!
        listed.items.single().status shouldBe FilingGateOverrideEntry.Status.SUPERSEDED
        w.store.rows
            .single()
            .status shouldBe OverrideStatus.PENDING
    }

    @Test
    fun `an approval answered AwaitsSecond releases nothing`() {
        val w = World()
        w.override("A1")
        w.decision = { ReleaseOutcome.AwaitsSecond }
        refused { w.approve("A2") } shouldBe ClosedErrorCode.ACCESS_DENIED
        w.store.rows
            .single()
            .status shouldBe OverrideStatus.PENDING
        w.filing() shouldBe Refuse()
    }

    // ---- getFilingGateOverrides -----------------------------------------------------------------------------------

    @Test
    fun `get lists the entries of one period and a caller without the permission is 403`() {
        val w = World()
        w.override("A1")
        w.asUser("A3")
        val items =
            w.controller
                .getFilingGateOverrides("E1", "P1", 100, null)
                .body!!
                .items
        items.single().checkId shouldBe "chk-2"
        items.single().status shouldBe FilingGateOverrideEntry.Status.PENDING
        items.single().requestedBy shouldBe "A1"
        w.controller
            .getFilingGateOverrides("E1", "P2", 100, null)
            .body!!
            .items shouldBe emptyList()
        w.asUser("A9")
        refused { w.controller.getFilingGateOverrides("E1", "P1", 100, null) } shouldBe ClosedErrorCode.ACCESS_DENIED
    }

    @Test
    fun `get pages by cursor and refuses a limit or a cursor outside the contract`() {
        val w = World()
        w.checks.known = (1..5).map { "c$it" }.toSet()
        listOf("c1", "c2", "c3", "c4", "c5").forEach {
            w.store.insert(NewOverride("E1", "P1", it, "r", OverrideStatus.PENDING, "A1", null))
        }
        w.asUser("A2")
        val first = w.controller.getFilingGateOverrides("E1", "P1", 2, null).body!!
        first.items.map { it.checkId } shouldBe listOf("c1", "c2")
        val second = w.controller.getFilingGateOverrides("E1", "P1", 2, first.nextCursor).body!!
        second.items.map { it.checkId } shouldBe listOf("c3", "c4")
        val third = w.controller.getFilingGateOverrides("E1", "P1", 2, second.nextCursor).body!!
        third.items.map { it.checkId } shouldBe listOf("c5")
        third.nextCursor shouldBe null
        val list = { limit: Int, cursor: String? -> w.controller.getFilingGateOverrides("E1", "P1", limit, cursor) }
        refused { list(0, null) } shouldBe ClosedErrorCode.CONTRACT_VIOLATION
        refused { list(501, null) } shouldBe ClosedErrorCode.CONTRACT_VIOLATION
        refused { list(10, "!!") } shouldBe ClosedErrorCode.CONTRACT_VIOLATION
    }

    // ---- INV-AC-11513 (filing-gate part) --------------------------------------------------------------------------

    @Test
    fun `INV-AC-11513 the empty reason is 400 with nothing stored, with a reason 201 and the filing proceeds`() {
        val w = World().also { it.decision = { ReleaseOutcome.Released } }
        refused { w.override("A1", reason = "") } shouldBe ClosedErrorCode.CONTRACT_VIOLATION
        w.store.rows shouldBe emptyList()
        w.audit.entries shouldBe emptyList()
        w.override("A1").statusCode.value() shouldBe 201
        w.audit.entries shouldBe listOf("REQUESTED:A1:supplier fix", "RELEASED:A1:A1")
        w.filing() shouldBe proceeds
    }

    @Test
    fun `INV-AC-11513 with a second approver the answer is 202 and the filing stays refused with PER-001`() {
        val w = World()
        w.override("A1").statusCode.value() shouldBe 202
        w.filing() shouldBe Refuse(REFUSAL_CODE)
    }
}
