package com.dynokore.gst.access.application.consultant

import com.dynokore.gst.access.application.grant.AuthorityChangePublisher
import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantAuditEntry
import com.dynokore.gst.access.application.grant.GrantRepository
import com.dynokore.gst.access.application.grant.GrantRoleEffects
import com.dynokore.gst.access.application.grant.GrantRoleOutcome
import com.dynokore.gst.access.application.grant.GrantRoleRules
import com.dynokore.gst.access.application.grant.GrantRoleUseCase
import com.dynokore.gst.access.application.grant.RoleGrantChange
import com.dynokore.gst.access.application.review.VpAvailability
import com.dynokore.gst.access.application.separation.ConflictingRolePairs
import com.dynokore.gst.access.application.separation.SoleOperatorAck
import com.dynokore.gst.access.application.separation.SoleOperatorAcks
import com.dynokore.gst.access.application.separation.SoleOperatorScope
import com.dynokore.gst.access.domain.consultant.ConsultantGrantAsk
import com.dynokore.gst.access.domain.consultant.ConsultantGrantRecord
import com.dynokore.gst.access.domain.consultant.ConsultantRequestStatus
import com.dynokore.gst.access.domain.grant.Grant
import com.dynokore.gst.access.domain.grant.GrantRef
import com.dynokore.gst.access.domain.grant.GranteeKind
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.gst.access.domain.role.RoleCatalogue
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * INV-AC-24223 (INV-REQ-908, INV-REQ-2465): the request and decision use cases on in-memory ports. The writer is the
 * real `GrantRoleUseCase` over a recording repository, so the command it receives is observed at its ports.
 */
class ConsultantGrantUseCasesTest {
    private val tenant = TenantRef("T-1")
    private val consultant = IdentityRef("IDN-C")
    private val u1 = IdentityRef("user:u1")
    private val u2 = IdentityRef("user:u2")
    private val clock = Clock.fixed(Instant.parse("2027-01-05T10:00:00Z"), ZoneOffset.UTC)
    private val goLive = LocalDate.parse("2027-01-10")

    private val tx =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T = block()
        }

    private class Requests : ConsultantGrantRequests {
        val rows = linkedMapOf<String, ConsultantGrantRecord>()

        override fun findPending(ask: ConsultantGrantAsk) =
            rows.values.firstOrNull { it.ask == ask && it.status == ConsultantRequestStatus.PENDING }

        override fun insert(ask: ConsultantGrantAsk): ConsultantGrantRecord {
            val record =
                ConsultantGrantRecord("CGR-${rows.size + 1}", ask, ConsultantRequestStatus.PENDING, null, null, null, 0)
            rows[record.ref] = record
            return record
        }

        override fun lock(ref: String) = rows[ref]

        override fun update(
            before: ConsultantGrantRecord,
            after: ConsultantGrantRecord,
        ) {
            rows[before.ref] = after
        }
    }

    private class Ports :
        GrantRepository,
        AuthorityChangePublisher,
        GrantAudit {
        val grants = mutableListOf<Grant>()
        val kinds = mutableListOf<GranteeKind>()
        val audit = mutableListOf<GrantAuditEntry>()
        var next = 0L

        override fun findByIdentity(identity: IdentityRef) = grants.filter { it.identity == identity }

        override fun find(ref: GrantRef) = grants.firstOrNull { it.ref == ref }

        override fun findByRepeatKey(
            identity: IdentityRef,
            roleWire: String,
            scope: Scope,
        ) = grants.firstOrNull { it.identity == identity && it.role.wire == roleWire && it.scope == scope }

        override fun insert(grant: Grant) {
            grants += grant
        }

        override fun update(
            before: Grant,
            after: Grant,
        ) = error("not used")

        override fun lockIdentity(
            identity: IdentityRef,
            kind: GranteeKind,
        ) {
            kinds += kind
        }

        override fun nextAuthorityVersion() = ++next

        override fun nextGrantRef() = GrantRef("G-${++next}")

        override fun publish(changes: List<RoleGrantChange>) = Unit

        override fun record(entry: GrantAuditEntry) {
            audit += entry
        }
    }

    private val noAck =
        object : SoleOperatorAcks {
            override fun findActive(): SoleOperatorAck? = null

            override fun find(ref: String): SoleOperatorAck? = null

            override fun lockActive(): SoleOperatorAck? = null

            override fun holders(role: Role): Set<IdentityRef> = emptySet()

            override fun insert(compensatingControls: String): SoleOperatorAck = error("not used")

            override fun withdraw(ref: String) = error("not used")
        }

    private inner class Rig(
        vp69: VpAvailability = VpAvailability.AVAILABLE,
        catalogue: RoleCatalogue = RoleCatalogue.ALL,
    ) {
        val requests = Requests()
        val ports = Ports()
        val decisionAudit = Ports()
        val writer =
            GrantRoleUseCase(
                tx,
                ports,
                GrantRoleEffects(ports, ports),
                GrantRoleRules(catalogue, noAck, { SoleOperatorScope.ONE_HOLDER_WITHDRAW_BLOCKED }, { emptySet() }),
            )
        val request = RequestConsultantGrantUseCase(tx, requests, decisionAudit)
        val decide = DecideConsultantGrantUseCase(tx, requests, writer, { vp69 }, decisionAudit, clock)

        fun pending(
            by: IdentityRef = u1,
            at: LocalDate = goLive,
        ) = request.handle(ConsultantGrantAsk(tenant, consultant, at, by))
    }

    @Test
    fun `INV-AC-24223 a request is stored pending and audited with the acting user ID`() {
        val rig = Rig()
        val r = rig.pending()
        assertEquals(ConsultantRequestStatus.PENDING, r.status)
        assertEquals(listOf("user:u1"), rig.decisionAudit.audit.map { it.actor })
        assertEquals(r, rig.pending())
        assertEquals(1, rig.requests.rows.size)
        assertEquals(1, rig.decisionAudit.audit.size)
    }

    @Test
    fun `INV-AC-24223 approval writes one EXTERNAL consultant grant of tenant scope ending go-live plus 30 days`() {
        val rig = Rig()
        val r = rig.pending()
        val out = rig.decide.handle(u2, r.ref, true, null)
        val approved = assertInstanceOf(ConsultantDecisionOutcome.Approved::class.java, out).record
        assertEquals(ConsultantRequestStatus.APPROVED, approved.status)
        assertEquals(LocalDate.parse("2027-02-09"), approved.validTo)
        val grant = rig.ports.grants.single()
        assertEquals(Role.IMPLEMENTATION_CONSULTANT, grant.role)
        assertEquals(Scope.Tenant(tenant), grant.scope)
        assertEquals(listOf(GranteeKind.EXTERNAL), rig.ports.kinds)
        assertEquals(Instant.parse("2027-01-05T00:00:00Z"), grant.window.from)
        assertEquals(Instant.parse("2027-02-09T00:00:00Z"), grant.window.until)
        assertEquals(listOf("user:u2"), rig.ports.audit.map { it.actor })
        assertTrue(rig.decisionAudit.audit.map { it.actor }.all { it.startsWith("user:") })
        assertEquals("user:u2", rig.decisionAudit.audit.last().actor)
    }

    @Test
    fun `INV-AC-24223 a denial writes no grant and marks the request denied`() {
        val rig = Rig()
        val r = rig.pending()
        val out = rig.decide.handle(u2, r.ref, false, null)
        val denied = assertInstanceOf(ConsultantDecisionOutcome.Denied::class.java, out).record
        assertEquals(ConsultantRequestStatus.DENIED, denied.status)
        assertTrue(rig.ports.grants.isEmpty())
        assertTrue(rig.ports.kinds.isEmpty())
        assertEquals(ConsultantRequestStatus.DENIED, rig.requests.rows.getValue(r.ref).status)
    }

    @Test
    fun `INV-AC-24223 the requester cannot approve and nothing is written`() {
        val rig = Rig()
        val r = rig.pending()
        assertEquals(ConsultantDecisionOutcome.SameActor, rig.decide.handle(u1, r.ref, true, null))
        assertEquals(ConsultantDecisionOutcome.SameActor, rig.decide.handle(u1, r.ref, false, null))
        assertTrue(rig.ports.grants.isEmpty())
        assertEquals(ConsultantRequestStatus.PENDING, rig.requests.rows.getValue(r.ref).status)
    }

    @Test
    fun `INV-AC-24223 a second decision on a decided request is NotPending and writes nothing`() {
        val rig = Rig()
        val r = rig.pending()
        rig.decide.handle(u2, r.ref, true, null)
        assertEquals(ConsultantDecisionOutcome.NotPending, rig.decide.handle(u2, r.ref, true, null))
        assertEquals(ConsultantDecisionOutcome.NotPending, rig.decide.handle(u2, r.ref, false, null))
        assertEquals(1, rig.ports.grants.size)
        assertEquals(ConsultantDecisionOutcome.NotFound, rig.decide.handle(u2, "CGR-99", true, null))
    }

    @Test
    fun `INV-AC-24223 an end not after the approval date is EndsNotAfterApproval`() {
        val rig = Rig()
        val past = rig.pending(at = LocalDate.parse("2026-12-01"))
        assertEquals(ConsultantDecisionOutcome.EndsNotAfterApproval, rig.decide.handle(u2, past.ref, true, 30))
        // go-live 2026-12-06 plus 30 days is exactly the approval date 2027-01-05: not after it
        val edge = rig.pending(at = LocalDate.parse("2026-12-06"))
        assertEquals(ConsultantDecisionOutcome.EndsNotAfterApproval, rig.decide.handle(u2, edge.ref, true, 30))
        assertTrue(rig.ports.grants.isEmpty())
        assertEquals(ConsultantRequestStatus.PENDING, rig.requests.rows.getValue(edge.ref).status)
    }

    @Test
    fun `INV-AC-24223 121 days is PeriodOutOfRange and a planned VP-69 accepts only the default`() {
        val rig = Rig()
        val r = rig.pending()
        assertEquals(ConsultantDecisionOutcome.PeriodOutOfRange, rig.decide.handle(u2, r.ref, true, 121))
        val planned = Rig(vp69 = VpAvailability.PLANNED)
        val p = planned.pending()
        assertEquals(ConsultantDecisionOutcome.PeriodNotAvailable, planned.decide.handle(u2, p.ref, true, 31))
        assertInstanceOf(ConsultantDecisionOutcome.Approved::class.java, planned.decide.handle(u2, p.ref, true, 30))
        assertTrue(rig.ports.grants.isEmpty())
    }

    @Test
    fun `INV-AC-24223 a refused writer leaves the request pending and writes no grant`() {
        val rig = Rig(catalogue = RoleCatalogue(Role.entries.toSet() - Role.IMPLEMENTATION_CONSULTANT))
        val r = rig.pending()
        val out = rig.decide.handle(u2, r.ref, true, null)
        val refused = assertInstanceOf(ConsultantDecisionOutcome.GrantRefused::class.java, out)
        assertEquals(GrantRoleOutcome.UnknownRole(Role.IMPLEMENTATION_CONSULTANT.wire), refused.outcome)
        assertEquals(ConsultantRequestStatus.PENDING, rig.requests.rows.getValue(r.ref).status)
        assertTrue(rig.ports.grants.isEmpty())
    }

    @Test
    fun `INV-AC-24223 the permission keys are the constants of the criterion`() {
        assertEquals("consultant-grant.request", ConsultantGrantKeys.REQUEST.value)
        assertEquals("user.manage", ConsultantGrantKeys.APPROVE.value)
    }
}
