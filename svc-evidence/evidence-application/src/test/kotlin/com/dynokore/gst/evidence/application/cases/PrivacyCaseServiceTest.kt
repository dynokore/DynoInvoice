package com.dynokore.gst.evidence.application.cases

import com.dynokore.gst.evidence.application.cases.CloseResult.Closed
import com.dynokore.gst.evidence.application.cases.OpenResult.Opened
import com.dynokore.gst.evidence.domain.cases.CaseKind
import com.dynokore.gst.evidence.domain.cases.CaseOutcome
import com.dynokore.gst.evidence.domain.cases.CaseRecord
import com.dynokore.gst.evidence.domain.cases.CaseStatus
import com.dynokore.gst.evidence.domain.cases.RequestKind
import com.dynokore.gst.evidence.domain.cases.SecurityCaseFacts
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class PrivacyCaseServiceTest {
    private class MemoryStore : PrivacyCaseStore {
        val rows = mutableMapOf<Pair<TenantId, UUID>, CaseRecord>()

        override fun insert(
            tenant: TenantId,
            case: CaseRecord,
        ) {
            rows[tenant to case.caseId] = case
        }

        override fun find(
            tenant: TenantId,
            caseId: UUID,
        ): CaseRecord? = rows[tenant to caseId]

        override fun findByRequestReference(
            tenant: TenantId,
            requestReference: String,
        ): CaseRecord? =
            rows.entries
                .firstOrNull { it.key.first == tenant && it.value.privacy?.requestReference == requestReference }
                ?.value

        override fun update(
            tenant: TenantId,
            case: CaseRecord,
        ) {
            val old = requireNotNull(rows[tenant to case.caseId]) { "no such row" }
            rows[tenant to case.caseId] =
                old.copy(status = case.status, privacy = old.privacy?.copy(closure = case.privacy?.closure))
        }

        override fun markNoticePrompted(
            tenant: TenantId,
            caseId: UUID,
            at: Instant,
        ) = error("not used")

        override fun findUnprompted(tenant: TenantId): List<CaseRecord> = error("not used")
    }

    private class RecordingAuthorizer(
        private val refuse: Boolean = false,
    ) : CaseAuthorizer {
        val keys = mutableListOf<String>()

        override fun require(
            tenant: TenantId,
            principal: Principal,
            permission: String,
        ) {
            keys += permission
            if (refuse) throw CaseAccessDenied(permission)
        }
    }

    private class FakePolicy(
        var tenantDays: Int? = null,
    ) : PolicyValues {
        override fun packResponseDays() = 30

        override fun tenantResponseDays(
            tenant: TenantId,
            on: LocalDate,
        ) = tenantDays

        override fun noticeLeadDays(
            tenant: TenantId,
            on: LocalDate,
        ) = 7
    }

    private val tenantA = TenantId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
    private val principal = Principal("user-1", PrincipalKind.HUMAN)
    private val now = Instant.parse("2026-03-10T01:02:03Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val caseId = UUID.fromString("33333333-3333-3333-3333-333333333333")
    private val received = LocalDate.parse("2026-03-01")
    private val today = LocalDate.parse("2026-03-01")

    private fun command(
        subjectRef: String = "subject-1",
        kind: RequestKind = RequestKind.ACCESS,
        receivedOn: LocalDate = received,
    ) = OpenPrivacyCaseCommand(subjectRef, kind, receivedOn, "REQ-1")

    private fun service(
        store: PrivacyCaseStore,
        authorizer: CaseAuthorizer = RecordingAuthorizer(),
        policy: PolicyValues = FakePolicy(),
        clock: Clock = this.clock,
    ) = PrivacyCaseService(store, authorizer, policy, { caseId }, clock)

    private fun opened(
        store: MemoryStore,
        policy: PolicyValues = FakePolicy(),
    ) = (service(store, policy = policy).open(tenantA, principal, command(), today) as Opened).record

    @Test
    fun `INV-AC-11707 open stores the deadline 2026-03-31 with period 30`() {
        val store = MemoryStore()
        val record = opened(store)
        store.rows.getValue(tenantA to caseId) shouldBe record
        record.kind shouldBe CaseKind.PRIVACY
        record.status shouldBe CaseStatus.OPEN
        record.openedAt shouldBe now
        record.privacy!!.responseDeadline shouldBe LocalDate.parse("2026-03-31")
        record.privacy!!.deadlinePeriodDays shouldBe 30
    }

    @Test
    fun `INV-AC-11707 a tenant period of 14 gives 2026-03-15`() {
        val record = opened(MemoryStore(), FakePolicy(14))
        record.privacy!!.responseDeadline shouldBe LocalDate.parse("2026-03-15")
        record.privacy!!.deadlinePeriodDays shouldBe 14
    }

    @Test
    fun `INV-AC-11707 a tenant period of 45 throws and stores nothing`() {
        val store = MemoryStore()
        shouldThrow<IllegalArgumentException> {
            service(store, policy = FakePolicy(45)).open(tenantA, principal, command(), today)
        }
        store.rows.size shouldBe 0
    }

    @Test
    fun `INV-AC-11707 receivedOn after today throws ReceivedInFuture and stores nothing`() {
        val store = MemoryStore()
        shouldThrow<ReceivedInFuture> {
            service(store).open(tenantA, principal, command(receivedOn = LocalDate.parse("2026-03-02")), today)
        }
        store.rows.size shouldBe 0
    }

    @Test
    fun `INV-AC-11707 a changed tenant period after opening leaves the stored deadline equal`() {
        val store = MemoryStore()
        val policy = FakePolicy()
        opened(store, policy)
        policy.tenantDays = 14
        val again = service(store, policy = policy).open(tenantA, principal, command(), today)
        (again as OpenResult.Replayed).record.privacy!!.responseDeadline shouldBe LocalDate.parse("2026-03-31")
        store.rows
            .getValue(tenantA to caseId)
            .privacy!!
            .responseDeadline shouldBe LocalDate.parse("2026-03-31")
    }

    @Test
    fun `INV-AC-11707 the same reference and data replay with one record`() {
        val store = MemoryStore()
        val first = opened(store)
        val second = service(store).open(tenantA, principal, command(), today)
        second shouldBe OpenResult.Replayed(first)
        store.rows.size shouldBe 1
    }

    @Test
    fun `INV-AC-11707 the same reference with another date, subjectRef or kind is a ReferenceConflict`() {
        val store = MemoryStore()
        opened(store)
        val others =
            listOf(
                command(receivedOn = LocalDate.parse("2026-02-28")),
                command(subjectRef = "subject-2"),
                command(kind = RequestKind.CORRECTION),
            )
        for (other in others) {
            shouldThrow<ReferenceConflict> { service(store).open(tenantA, principal, other, today) }
        }
        store.rows.size shouldBe 1
    }

    @Test
    fun `INV-AC-11707 a refusing authorizer on open changes nothing and asks privacy-request dot handle`() {
        val store = MemoryStore()
        val authorizer = RecordingAuthorizer(refuse = true)
        shouldThrow<CaseAccessDenied> { service(store, authorizer).open(tenantA, principal, command(), today) }
        store.rows.size shouldBe 0
        authorizer.keys shouldBe listOf("privacy-request.handle")
    }

    @Test
    fun `INV-AC-11707 close stores CLOSED with the closure and the deadline unchanged`() {
        val store = MemoryStore()
        val policy = FakePolicy(14)
        val record = opened(store, policy)
        policy.tenantDays = 7
        val later = Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC)
        val result =
            service(
                store,
                policy = policy,
                clock = later,
            ).close(tenantA, principal, caseId, CaseOutcome.ANSWERED, "answered")
        val closed = (result as Closed).record
        closed.status shouldBe CaseStatus.CLOSED
        closed.privacy!!.closure!!.closedAt shouldBe now.plusSeconds(60)
        closed.privacy!!.closure!!.outcome shouldBe CaseOutcome.ANSWERED
        closed.privacy!!.responseDeadline shouldBe record.privacy!!.responseDeadline
        closed.privacy!!.responseDeadline shouldBe LocalDate.parse("2026-03-15")
        closed.privacy!!.deadlinePeriodDays shouldBe 14
        store.rows.getValue(tenantA to caseId) shouldBe closed
    }

    @Test
    fun `INV-AC-11707 a PRIVACY record without privacy facts throws NotAPrivacyCase and stays unchanged`() {
        val store = MemoryStore()
        val bare = CaseRecord(caseId, CaseKind.PRIVACY, CaseStatus.OPEN, "subject-1", now, null, null)
        store.insert(tenantA, bare)
        shouldThrow<NotAPrivacyCase> { service(store).close(tenantA, principal, caseId, CaseOutcome.ANSWERED, "b") }
        store.rows.getValue(tenantA to caseId) shouldBe bare
    }

    @Test
    fun `INV-AC-11707 the same outcome and basis again is Replayed with the first closedAt`() {
        val store = MemoryStore()
        opened(store)
        val first = (service(store).close(tenantA, principal, caseId, CaseOutcome.ANSWERED, "b") as Closed).record
        val later = Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC)
        val again = service(store, clock = later).close(tenantA, principal, caseId, CaseOutcome.ANSWERED, "b")
        again shouldBe CloseResult.Replayed(first)
        store.rows
            .getValue(tenantA to caseId)
            .privacy!!
            .closure!!
            .closedAt shouldBe now
    }

    @Test
    fun `INV-AC-11707 another outcome or basis on a CLOSED case throws CloseConflict`() {
        val store = MemoryStore()
        opened(store)
        service(store).close(tenantA, principal, caseId, CaseOutcome.ANSWERED, "b")
        shouldThrow<CloseConflict> { service(store).close(tenantA, principal, caseId, CaseOutcome.REFUSED, "b") }
        shouldThrow<CloseConflict> { service(store).close(tenantA, principal, caseId, CaseOutcome.ANSWERED, "c") }
    }

    @Test
    fun `INV-AC-11707 a SECURITY record throws NotAPrivacyCase and an unknown id gives NotFound`() {
        val store = MemoryStore()
        val security =
            CaseRecord(
                caseId,
                CaseKind.SECURITY,
                CaseStatus.OPEN,
                null,
                now,
                SecurityCaseFacts("R", listOf(UUID.randomUUID()), "O", false),
            )
        store.insert(tenantA, security)
        shouldThrow<NotAPrivacyCase> { service(store).close(tenantA, principal, caseId, CaseOutcome.ANSWERED, "b") }
        store.rows.getValue(tenantA to caseId) shouldBe security
        service(MemoryStore()).close(tenantA, principal, caseId, CaseOutcome.ANSWERED, "b") shouldBe
            CloseResult.NotFound
    }

    @Test
    fun `INV-AC-11707 basis of 500 characters passes and 501, 0 and an unknown outcome are refused`() {
        val store = MemoryStore()
        opened(store)
        shouldThrow<IllegalArgumentException> {
            service(store).close(tenantA, principal, caseId, CaseOutcome.ANSWERED, "b".repeat(501))
        }
        shouldThrow<IllegalArgumentException> {
            service(
                store,
            ).close(tenantA, principal, caseId, CaseOutcome.ANSWERED, "")
        }
        shouldThrow<IllegalArgumentException> { CaseOutcome.valueOf("DEFERRED") }
        store.rows.getValue(tenantA to caseId).status shouldBe CaseStatus.OPEN
        service(store).close(tenantA, principal, caseId, CaseOutcome.ANSWERED, "b".repeat(500)) shouldBe
            CloseResult.Closed(store.rows.getValue(tenantA to caseId))
    }

    @Test
    fun `INV-AC-11707 a refusing authorizer on close changes nothing and asks privacy-request dot handle`() {
        val store = MemoryStore()
        val record = opened(store)
        val authorizer = RecordingAuthorizer(refuse = true)
        shouldThrow<CaseAccessDenied> {
            service(store, authorizer).close(tenantA, principal, caseId, CaseOutcome.ANSWERED, "b")
        }
        store.rows.getValue(tenantA to caseId) shouldBe record
        authorizer.keys shouldBe listOf("privacy-request.handle")
    }
}
