package com.dynokore.gst.evidence.application.cases

import com.dynokore.gst.evidence.domain.cases.CaseKind
import com.dynokore.gst.evidence.domain.cases.CaseRecord
import com.dynokore.gst.evidence.domain.cases.CaseStatus
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class CaseServiceTest {
    private class MemoryStore : CaseStore {
        val rows = mutableMapOf<Pair<TenantId, UUID>, CaseRecord>()
        var finds = 0

        override fun insert(
            tenant: TenantId,
            case: CaseRecord,
        ) {
            rows[tenant to case.caseId] = case
        }

        override fun find(
            tenant: TenantId,
            caseId: UUID,
        ): CaseRecord? {
            finds++
            return rows[tenant to caseId]
        }
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

    private val tenantA = TenantId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
    private val tenantB = TenantId(UUID.fromString("22222222-2222-2222-2222-222222222222"))
    private val principal = Principal("user-1", PrincipalKind.HUMAN)
    private val now = Instant.parse("2026-10-28T01:02:03Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val caseId = UUID.fromString("33333333-3333-3333-3333-333333333333")
    private val e1 = UUID.fromString("44444444-4444-4444-4444-444444444441")
    private val e2 = UUID.fromString("44444444-4444-4444-4444-444444444442")

    private fun command(exposed: Boolean) =
        RecordSecurityCaseCommand("REVIEW-2026-0142", listOf(e1, e2), "CREDENTIALS_ROTATED", exposed)

    private fun service(
        store: CaseStore,
        authorizer: CaseAuthorizer,
        readPermission: String = "case.read",
    ) = CaseService(store, authorizer, readPermission, { caseId }, clock)

    @Test
    fun `INV-AC-11696 personalDataExposed true opens the breach assessment`() {
        val store = MemoryStore()
        val record = service(store, RecordingAuthorizer()).recordSecurityCase(tenantA, principal, command(true))
        record.security!!.breachAssessmentOpened shouldBe true
    }

    @Test
    fun `INV-AC-11696 personalDataExposed false leaves the breach assessment closed`() {
        val store = MemoryStore()
        val record = service(store, RecordingAuthorizer()).recordSecurityCase(tenantA, principal, command(false))
        record.security!!.breachAssessmentOpened shouldBe false
    }

    @Test
    fun `INV-AC-11696 the stored record equals the returned one field by field`() {
        val store = MemoryStore()
        val record = service(store, RecordingAuthorizer()).recordSecurityCase(tenantA, principal, command(true))
        store.rows.size shouldBe 1
        val stored = store.rows.getValue(tenantA to caseId)
        stored shouldBe record
        stored.caseId shouldBe caseId
        stored.kind shouldBe CaseKind.SECURITY
        stored.status shouldBe CaseStatus.OPEN
        stored.subjectRef shouldBe null
        stored.openedAt shouldBe now
        stored.security!!.reviewReference shouldBe "REVIEW-2026-0142"
        stored.security!!.auditLogEntryIds shouldBe listOf(e1, e2)
        stored.security!!.outcome shouldBe "CREDENTIALS_ROTATED"
    }

    @Test
    fun `INV-AC-11696 a refusal on recordSecurityCase stores nothing`() {
        val store = MemoryStore()
        shouldThrow<CaseAccessDenied> {
            service(store, RecordingAuthorizer(refuse = true)).recordSecurityCase(tenantA, principal, command(true))
        }
        store.rows.size shouldBe 0
    }

    @Test
    fun `INV-AC-11696 a refusal on getCase makes no find call`() {
        val store = MemoryStore()
        shouldThrow<CaseAccessDenied> {
            service(store, RecordingAuthorizer(refuse = true)).getCase(tenantA, principal, caseId)
        }
        store.finds shouldBe 0
    }

    @Test
    fun `INV-AC-11696 recordSecurityCase asks for security-case record`() {
        val authorizer = RecordingAuthorizer()
        service(MemoryStore(), authorizer, "x").recordSecurityCase(tenantA, principal, command(false))
        authorizer.keys shouldBe listOf(CasePermissions.SECURITY_CASE_RECORD)
        CasePermissions.SECURITY_CASE_RECORD shouldBe "security-case.record"
        CasePermissions.PRIVACY_REQUEST_HANDLE shouldBe "privacy-request.handle"
    }

    @Test
    fun `INV-AC-11696 getCase asks for the read key given to the constructor`() {
        for (key in listOf("read.one", "read.two")) {
            val authorizer = RecordingAuthorizer()
            service(MemoryStore(), authorizer, key).getCase(tenantA, principal, caseId)
            authorizer.keys shouldBe listOf(key)
        }
    }

    @Test
    fun `INV-AC-11696 getCase gives the stored record, null for an unknown id and null for another tenant`() {
        val store = MemoryStore()
        val service = service(store, RecordingAuthorizer())
        val record = service.recordSecurityCase(tenantA, principal, command(true))
        service.getCase(tenantA, principal, caseId) shouldBe record
        service.getCase(tenantA, principal, UUID.fromString("55555555-5555-5555-5555-555555555555")) shouldBe null
        service.getCase(tenantB, principal, caseId) shouldBe null
    }
}
