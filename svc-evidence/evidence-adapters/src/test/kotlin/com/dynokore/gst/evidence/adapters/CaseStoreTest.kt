package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.persistence.JooqCaseStore
import com.dynokore.gst.evidence.domain.cases.CaseKind
import com.dynokore.gst.evidence.domain.cases.CaseRecord
import com.dynokore.gst.evidence.domain.cases.CaseStatus
import com.dynokore.gst.evidence.domain.cases.SecurityCaseFacts
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * INV-AC-11697 (INV-Q-11680 A): `case_register` stores a security or privacy case under forced tenant isolation with
 * database checks, and `JooqCaseStore` reads back what it wrote.
 */
class CaseStoreTest {
    private val owner = UUID.randomUUID()
    private val other = UUID.randomUUID()
    private val openedAt = Instant.now().truncatedTo(ChronoUnit.MICROS)

    private fun security(ids: List<UUID> = listOf(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())) =
        CaseRecord(
            caseId = UUID.randomUUID(),
            kind = CaseKind.SECURITY,
            status = CaseStatus.OPEN,
            subjectRef = null,
            openedAt = openedAt,
            security = SecurityCaseFacts("REVIEW-1", ids, "credential rotated", true),
        )

    private fun privacy(subjectRef: String = "s".repeat(60)) =
        CaseRecord(UUID.randomUUID(), CaseKind.PRIVACY, CaseStatus.EXPORT_READY, subjectRef, openedAt, null)

    /** A runtime session bound to [tenant], committed at the end. */
    private fun <T> bound(
        tenant: UUID,
        block: (Connection) -> T,
    ): T =
        EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { connection ->
            connection.autoCommit = false
            SignedBindings.bind(connection, tenant)
            block(connection).also { connection.commit() }
        }

    private fun store(connection: Connection) = JooqCaseStore(DSL.using(connection, SQLDialect.POSTGRES))

    /** The SQLSTATE a raw statement is refused with as the runtime login bound to [owner]; rolled back. */
    private fun refusedWith(sql: String): String? =
        EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { connection ->
            connection.autoCommit = false
            SignedBindings.bind(connection, owner)
            val result = runCatching { connection.createStatement().use { it.execute(sql) } }
            connection.rollback()
            result.exceptionOrNull()?.let { (it as SQLException).sqlState }
        }

    private fun securityInsert(
        subjectRef: String = "null",
        outcome: String = "'ok'",
        ids: String = "array[gen_random_uuid()]",
        kind: String = "SECURITY",
        status: String = "OPEN",
        reviewReference: String = "'R'",
    ) = "insert into evidence.case_register (tenant_id, case_id, case_kind, status, subject_ref, opened_at, " +
        "review_reference, audit_log_entry_ids, outcome, breach_assessment_opened) values ('$owner', " +
        "gen_random_uuid(), '$kind', '$status', $subjectRef, now(), $reviewReference, $ids, $outcome, false)"

    private fun privacyInsert(
        subjectRef: String = "'s'",
        reviewReference: String = "null",
    ) = "insert into evidence.case_register (tenant_id, case_id, case_kind, status, subject_ref, opened_at, " +
        "review_reference) values ('$owner', gen_random_uuid(), 'PRIVACY', 'OPEN', $subjectRef, now(), " +
        reviewReference + ")"

    @Test
    fun `INV-AC-11697 a SECURITY record reads back equal, ids in the order given with duplicates kept`() {
        val dup = UUID.randomUUID()
        val record = security(listOf(dup, UUID.randomUUID(), dup))
        bound(owner) { store(it).insert(TenantId(owner), record) }
        val found = bound(owner) { store(it).find(TenantId(owner), record.caseId) }
        found shouldBe record
        found!!.security!!.auditLogEntryIds shouldBe record.security!!.auditLogEntryIds
        found.subjectRef shouldBe null
        found.openedAt shouldBe openedAt
    }

    @Test
    fun `INV-AC-11697 a PRIVACY record with a 60 character subjectRef reads back equal`() {
        val record = privacy()
        bound(owner) { store(it).insert(TenantId(owner), record) }
        bound(owner) { store(it).find(TenantId(owner), record.caseId) } shouldBe record
    }

    @Test
    fun `INV-AC-11697 another tenant reads no row of the owner and an unknown id is null`() {
        val record = security()
        bound(owner) { store(it).insert(TenantId(owner), record) }
        bound(other) { store(it).find(TenantId(owner), record.caseId) } shouldBe null
        bound(other) { store(it).find(TenantId(other), record.caseId) } shouldBe null
        bound(owner) { store(it).find(TenantId(owner), UUID.randomUUID()) } shouldBe null
    }

    @Test
    fun `INV-AC-11697 a second insert of the same tenant and case throws`() {
        val record = privacy()
        bound(owner) { store(it).insert(TenantId(owner), record) }
        shouldThrow<DataAccessException> { bound(owner) { store(it).insert(TenantId(owner), record) } }
    }

    @Test
    fun `INV-AC-11697 an insert with another tenant than the bound one is refused by the policy`() {
        shouldThrow<DataAccessException> { bound(owner) { store(it).insert(TenantId(other), security()) } }
            .sqlState() shouldBe "42501"
    }

    @Test
    fun `INV-AC-11697 the valid raw rows are accepted, so each refusal below is due to its own check`() {
        refusedWith(securityInsert()) shouldBe null
        refusedWith(privacyInsert()) shouldBe null
    }

    @Test
    fun `INV-AC-11697 kind SECURITY with a subject_ref is refused`() {
        refusedWith(securityInsert(subjectRef = "'s'")) shouldBe "23514"
    }

    @Test
    fun `INV-AC-11697 kind PRIVACY with a review_reference is refused`() {
        refusedWith(privacyInsert(reviewReference = "'R'")) shouldBe "23514"
    }

    @Test
    fun `INV-AC-11697 status DONE is refused`() {
        refusedWith(securityInsert(status = "DONE")) shouldBe "23514"
    }

    @Test
    fun `INV-AC-11697 an empty audit_log_entry_ids for kind SECURITY is refused`() {
        refusedWith(securityInsert(ids = "'{}'::uuid[]")) shouldBe "23514"
    }

    @Test
    fun `INV-AC-11697 a 61 character subject_ref is refused and a 201 character outcome is refused`() {
        refusedWith(privacyInsert(subjectRef = "'${"s".repeat(61)}'")) shouldBe "23514"
        refusedWith(securityInsert(outcome = "'${"o".repeat(201)}'")) shouldBe "23514"
    }

    @Test
    fun `INV-AC-11697 UPDATE and DELETE are refused for the runtime login`() {
        val record = security()
        bound(owner) { store(it).insert(TenantId(owner), record) }
        refusedWith("update evidence.case_register set outcome = 'x'") shouldBe "42501"
        refusedWith("delete from evidence.case_register") shouldBe "42501"
    }
}
