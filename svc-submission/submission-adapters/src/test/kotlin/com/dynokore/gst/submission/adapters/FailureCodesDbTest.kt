package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.persistence.JooqFailureCodes
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Submission
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.SubmissionAttempt
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** The failure-code store as `submission_runtime`, and the widened check of `failure_class` (INV-T-20657). */
class FailureCodesDbTest {
    private val connection =
        SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).also { it.autoCommit = false }
    private val dsl = DSL.using(connection, SQLDialect.POSTGRES)
    private val codes = JooqFailureCodes(dsl)
    private val sub = Submission.SUBMISSION_
    private val att = SubmissionAttempt.SUBMISSION_ATTEMPT
    private val tenantA = UUID.randomUUID()
    private val tenantB = UUID.randomUUID()
    private val t0 = Instant.parse("2026-11-01T01:00:00Z")

    @AfterEach
    fun close() = connection.close()

    private fun <T> asTenant(
        tenant: UUID,
        block: () -> T,
    ): T {
        SignedBindings.bind(connection, tenant)
        return try {
            block().also { connection.commit() }
        } catch (e: Throwable) {
            connection.rollback()
            throw e
        }
    }

    private fun seed(
        tenant: UUID,
        doc: String,
        attempts: Int,
    ) = asTenant(tenant) {
        dsl
            .insertInto(sub)
            .set(sub.TENANT_ID, tenant)
            .set(sub.DOC_UUID, doc)
            .set(sub.VERSION, 1)
            .set(sub.STATE, "SENT")
            .set(sub.ENTITY_REF, "E-001")
            .execute()
        (1..attempts).forEach { n ->
            dsl
                .insertInto(att)
                .set(att.TENANT_ID, tenant)
                .set(att.DOC_UUID, doc)
                .set(att.VERSION, 1)
                .set(att.ATTEMPT_NUMBER, n)
                .set(att.CORRELATION_ID, UUID.randomUUID())
                .set(att.REQUESTED_AT, t0.plusSeconds(n.toLong()).atOffset(ZoneOffset.UTC))
                .execute()
        }
    }

    private fun stored(
        tenant: UUID,
        doc: String,
    ): List<String?> =
        asTenant(tenant) {
            dsl
                .select(att.FAILURE_CLASS)
                .from(att)
                .where(att.DOC_UUID.eq(doc))
                .orderBy(att.ATTEMPT_NUMBER)
                .fetch(att.FAILURE_CLASS)
        }

    @Test
    fun `INV-AC-20656 record stores the code of the attempt and lastOf reads the highest attempt`() {
        val doc = UUID.randomUUID().toString()
        seed(tenantA, doc, 2)
        asTenant(tenantA) { codes.lastOf(TenantId(tenantA), doc, 1) } shouldBe null

        asTenant(tenantA) { codes.record(TenantId(tenantA), doc, 1, 1, "SUB-001") }
        asTenant(tenantA) { codes.lastOf(TenantId(tenantA), doc, 1) } shouldBe null
        asTenant(tenantA) { codes.record(TenantId(tenantA), doc, 1, 2, "X-998") }

        stored(tenantA, doc) shouldBe listOf("SUB-001", "X-998")
        asTenant(tenantA) { codes.lastOf(TenantId(tenantA), doc, 1) } shouldBe "X-998"
    }

    @Test
    fun `INV-AC-20656 another tenant never reads or writes the code`() {
        val doc = UUID.randomUUID().toString()
        seed(tenantA, doc, 1)
        asTenant(tenantA) { codes.record(TenantId(tenantA), doc, 1, 1, "SUB-003") }

        asTenant(tenantB) { codes.lastOf(TenantId(tenantB), doc, 1) } shouldBe null
        asTenant(tenantB) { codes.lastOf(TenantId(tenantA), doc, 1) } shouldBe null
        shouldThrow<IllegalStateException> {
            asTenant(tenantB) { codes.record(TenantId(tenantB), doc, 1, 1, "SUB-001") }
        }
        stored(tenantA, doc) shouldBe listOf("SUB-003")
    }

    private fun Connection.fails(sql: String): String? =
        createStatement().use { st ->
            runCatching { st.execute(sql) }.exceptionOrNull()?.let { (it as SQLException).sqlState }
        }

    @Test
    fun `INV-AC-20656 the check accepts SUB-003 X-998 and X-999 and refuses the look-alikes`() {
        val doc = UUID.randomUUID().toString()
        seed(tenantA, doc, 1)
        SubmissionDb.superuser().use { c ->
            c.autoCommit = false
            try {
                fun set(value: String) =
                    "update submission.submission_attempt set failure_class = '$value' where doc_uuid = '$doc'"
                listOf("SUB-003", "X-998", "X-999").forEach { c.fails(set(it)) shouldBe null }
                listOf("X-997", "x-999", "X-9990", " X-999", "").forEach {
                    c.fails(set(it)) shouldBe "23514"
                    c.rollback()
                }
            } finally {
                c.rollback()
            }
        }
    }

    @Test
    fun `INV-AC-20656 a value written before the migration shape still satisfies the widened check`() {
        val doc = UUID.randomUUID().toString()
        seed(tenantA, doc, 1)
        asTenant(tenantA) { codes.record(TenantId(tenantA), doc, 1, 1, "SUB-001") }
        SubmissionDb.superuser().use { c ->
            val sql =
                "select count(*) from submission.submission_attempt where doc_uuid = '$doc' and failure_class = 'SUB-001'"
            c.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    rs.next() shouldBe true
                    rs.getInt(1) shouldBe 1
                }
            }
        }
    }
}
