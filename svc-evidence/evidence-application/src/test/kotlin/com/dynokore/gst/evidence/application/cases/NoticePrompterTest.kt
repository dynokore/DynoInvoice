package com.dynokore.gst.evidence.application.cases

import com.dynokore.gst.evidence.domain.cases.CaseClosure
import com.dynokore.gst.evidence.domain.cases.CaseKind
import com.dynokore.gst.evidence.domain.cases.CaseOutcome
import com.dynokore.gst.evidence.domain.cases.CaseRecord
import com.dynokore.gst.evidence.domain.cases.CaseStatus
import com.dynokore.gst.evidence.domain.cases.PrivacyCaseFacts
import com.dynokore.gst.evidence.domain.cases.RequestKind
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class NoticePrompterTest {
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
        ): CaseRecord? = null

        override fun update(
            tenant: TenantId,
            case: CaseRecord,
        ) = error("not used")

        override fun markNoticePrompted(
            tenant: TenantId,
            caseId: UUID,
            at: Instant,
        ) {
            val old = rows.getValue(tenant to caseId)
            rows[tenant to caseId] = old.copy(privacy = old.privacy!!.copy(noticePromptedAt = at))
        }

        override fun findUnprompted(tenant: TenantId): List<CaseRecord> =
            rows
                .filterKeys { it.first == tenant }
                .values
                .filter {
                    it.kind == CaseKind.PRIVACY && it.status != CaseStatus.CLOSED &&
                        it.privacy?.noticePromptedAt == null
                }
    }

    private class FakePolicy(
        val lead: Int = 7,
    ) : PolicyValues {
        override fun packResponseDays() = 30

        override fun tenantResponseDays(
            tenant: TenantId,
            on: LocalDate,
        ): Int? = null

        override fun noticeLeadDays(
            tenant: TenantId,
            on: LocalDate,
        ) = lead
    }

    private class RecordingEvents : NoticeEvents {
        val recorded = mutableListOf<Triple<TenantId, UUID, Int>>()
        var store: MemoryStore? = null
        val markedWhenRecorded = mutableListOf<Boolean>()

        override fun record(
            tenant: TenantId,
            case: CaseRecord,
            noticeLeadDays: Int,
        ) {
            markedWhenRecorded += store!!
                .rows
                .getValue(tenant to case.caseId)
                .privacy!!
                .noticePromptedAt != null
            recorded += Triple(tenant, case.caseId, noticeLeadDays)
        }
    }

    private val tenantA = TenantId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
    private val tenantB = TenantId(UUID.fromString("22222222-2222-2222-2222-222222222222"))
    private val idA = UUID.fromString("33333333-3333-3333-3333-333333333333")
    private val idB = UUID.fromString("44444444-4444-4444-4444-444444444444")
    private val now = Instant.parse("2026-03-24T01:02:03Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val store = MemoryStore()
    private val events = RecordingEvents().also { it.store = store }

    private fun record(
        id: UUID,
        status: CaseStatus = CaseStatus.OPEN,
    ): CaseRecord {
        val closure = if (status == CaseStatus.CLOSED) CaseClosure(now, CaseOutcome.ANSWERED, "b") else null
        val facts =
            PrivacyCaseFacts(
                RequestKind.ACCESS,
                "REQ-$id",
                LocalDate.parse("2026-03-01"),
                LocalDate.parse("2026-03-31"),
                30,
                closure,
                null,
            )
        return CaseRecord(id, CaseKind.PRIVACY, status, "subject", now, null, facts)
    }

    private fun prompter(lead: Int = 7) = NoticePrompter(store, FakePolicy(lead), events, clock)

    private fun day(s: String) = LocalDate.parse(s)

    private fun prompted(id: UUID = idA) =
        store.rows
            .getValue(tenantA to id)
            .privacy!!
            .noticePromptedAt

    @Test
    fun `INV-AC-11707 nothing is prompted on 2026-03-23 with lead 7`() {
        store.insert(tenantA, record(idA))
        prompter().promptNotices(tenantA, day("2026-03-23")) shouldBe 0
        prompted() shouldBe null
        events.recorded.size shouldBe 0
    }

    @Test
    fun `INV-AC-11707 the case is prompted once on 2026-03-24 and not again on 2026-03-25`() {
        store.insert(tenantA, record(idA))
        prompter().promptNotices(tenantA, day("2026-03-24")) shouldBe 1
        prompted() shouldBe now
        events.recorded shouldBe listOf(Triple(tenantA, idA, 7))
        events.markedWhenRecorded shouldBe listOf(true)
        prompter().promptNotices(tenantA, day("2026-03-25")) shouldBe 0
        prompted() shouldBe now
        events.recorded.size shouldBe 1
    }

    @Test
    fun `INV-AC-11707 an overdue case never prompted before is prompted on 2026-04-01`() {
        store.insert(tenantA, record(idA))
        prompter().promptNotices(tenantA, day("2026-04-01")) shouldBe 1
        prompted() shouldBe now
    }

    @Test
    fun `INV-AC-11707 a CLOSED case is never prompted`() {
        store.insert(tenantA, record(idA, CaseStatus.CLOSED))
        prompter().promptNotices(tenantA, day("2026-04-01")) shouldBe 0
        prompted() shouldBe null
        events.recorded.size shouldBe 0
    }

    @Test
    fun `INV-AC-11707 a tenant lead of 14 prompts from 2026-03-17 and not on 2026-03-16`() {
        store.insert(tenantA, record(idA))
        prompter(14).promptNotices(tenantA, day("2026-03-16")) shouldBe 0
        prompter(14).promptNotices(tenantA, day("2026-03-17")) shouldBe 1
        events.recorded.single().third shouldBe 14
    }

    @Test
    fun `INV-AC-11707 a lead of 0 prompts on the deadline day and not the day before`() {
        store.insert(tenantA, record(idA))
        prompter(0).promptNotices(tenantA, day("2026-03-30")) shouldBe 0
        prompter(0).promptNotices(tenantA, day("2026-03-31")) shouldBe 1
    }

    @Test
    fun `INV-AC-11707 another tenant's case is not prompted by a call for tenant A`() {
        store.insert(tenantB, record(idB))
        prompter().promptNotices(tenantA, day("2026-04-01")) shouldBe 0
        store.rows
            .getValue(tenantB to idB)
            .privacy!!
            .noticePromptedAt shouldBe null
        events.recorded.size shouldBe 0
    }
}
