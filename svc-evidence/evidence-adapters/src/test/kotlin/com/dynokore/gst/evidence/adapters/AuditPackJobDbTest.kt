package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.persistence.JooqAuditPackJobs
import com.dynokore.gst.evidence.application.export.AuditPackExportJob
import com.dynokore.gst.evidence.application.export.AuditPackState
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** INV-AC-24320: the audit-pack job table and its jOOQ store. */
class AuditPackJobDbTest {
    private val stated = Instant.parse("2026-11-18T08:30:00Z")
    private val clock = Clock.fixed(stated, ZoneOffset.UTC)
    private val q1 = "2026-Q1"
    private val q2 = "2026-Q2"

    private fun <T> tx(
        tenant: UUID,
        block: (JooqAuditPackJobs) -> T,
    ): T =
        EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, tenant)
            val dsl: DSLContext = DSL.using(c, SQLDialect.POSTGRES)
            block(JooqAuditPackJobs(dsl, clock)).also { c.commit() }
        }

    private fun register(
        tenant: UUID,
        period: String = q1,
        by: String = "U1",
    ): AuditPackExportJob = tx(tenant) { it.register(TenantId(tenant), "E1", period, by) }

    private fun count(tenant: UUID): Int =
        EvidenceDb.superuser().use { c ->
            c.prepareStatement("select count(*) from evidence.audit_pack_job where tenant_id = ?").use {
                it.setObject(1, tenant)
                it.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private fun rowText(tenant: UUID): String =
        EvidenceDb.superuser().use { c ->
            c
                .prepareStatement(
                    "select string_agg(pack_id || '|' || state || '|' || requested_by || '|' || " +
                        "coalesce(ready_at::text, '') || '|' || coalesce(download_uri, '') || '|' || " +
                        "coalesce(contents::text, '') || '|' || coalesce(failure_reason, ''), ';' " +
                        "order by period_ref) " +
                        "from evidence.audit_pack_job where tenant_id = ?",
                ).use {
                    it.setObject(1, tenant)
                    it.executeQuery().use { rs ->
                        rs.next()
                        rs.getString(1)
                    }
                }
        }

    private fun refused(
        tenant: UUID,
        sql: String,
    ): SQLException =
        EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, tenant)
            val failure = runCatching { c.createStatement().use { it.execute(sql) } }.exceptionOrNull()
            c.rollback()
            failure as SQLException
        }

    private fun insertSql(
        tenant: UUID,
        state: String = "PENDING",
        by: String = "U1",
        reason: String? = null,
    ): String =
        "insert into evidence.audit_pack_job (tenant_id, pack_id, entity_ref, period_ref, state, requested_at, " +
            "requested_by, failure_reason) values ('$tenant', '${UUID.randomUUID()}', 'E9', '2026-Q9', '$state', " +
            "now(), '$by', ${reason?.let { "'$it'" } ?: "null"})"

    @Test
    fun `INV-AC-24320 the first register is PENDING with requestedBy U1, the clock instant and the given refs`() {
        val t1 = UUID.randomUUID()
        val job = register(t1)
        job.state shouldBe AuditPackState.PENDING
        job.requestedBy shouldBe "U1"
        job.requestedAt shouldBe stated
        job.tenant shouldBe TenantId(t1)
        job.entityRef shouldBe "E1"
        job.periodRef shouldBe q1
        job.readyAt shouldBe null
        job.downloadUri shouldBe null
        job.contents shouldBe emptyList()
        job.failureReason shouldBe null
    }

    @Test
    fun `INV-AC-24320 the repeat by U2 returns the same packId, keeps requestedBy U1 and leaves one row`() {
        val t1 = UUID.randomUUID()
        val first = register(t1)
        val second = register(t1, by = "U2")
        second.packId shouldBe first.packId
        second.requestedBy shouldBe "U1"
        second shouldBe first
        count(t1) shouldBe 1
    }

    @Test
    fun `INV-AC-24320 the second period gets its own packId`() {
        val t1 = UUID.randomUUID()
        val first = register(t1)
        val other = register(t1, period = q2)
        (other.packId == first.packId) shouldBe false
        other.periodRef shouldBe q2
        count(t1) shouldBe 2
    }

    @Test
    fun `INV-AC-24320 markReady stores the download URI, the contents in order and the clock instant`() {
        val t1 = UUID.randomUUID()
        val job = register(t1)
        val done = tx(t1) { it.markReady(job, "s3://p/1", listOf("B", "A", "C")) }
        done.state shouldBe AuditPackState.READY
        done.downloadUri shouldBe "s3://p/1"
        done.contents shouldBe listOf("B", "A", "C")
        done.readyAt shouldBe stated
        done.requestedBy shouldBe "U1"
        tx(t1) { it.find(TenantId(t1), "E1", q1, job.packId) } shouldBe done
        EvidenceDb.superuser().use { c ->
            c.prepareStatement("select ready_at from evidence.audit_pack_job where tenant_id = ?").use {
                it.setObject(1, t1)
                it.executeQuery().use { rs ->
                    rs.next()
                    rs.getObject(1, OffsetDateTime::class.java).toInstant() shouldBe stated
                }
            }
        }
    }

    @Test
    fun `INV-AC-24320 markFailed and markReady on a READY job return the stored job and change no row`() {
        val t1 = UUID.randomUUID()
        val job = register(t1)
        val ready = tx(t1) { it.markReady(job, "s3://p/1", listOf("A")) }
        val before = rowText(t1)
        tx(t1) { it.markFailed(job, "late") } shouldBe ready
        tx(t1) { it.markReady(job, "s3://p/2", listOf("Z")) } shouldBe ready
        rowText(t1) shouldBe before
    }

    @Test
    fun `INV-AC-24320 a FAILED job stays FAILED and the row is unchanged`() {
        val t1 = UUID.randomUUID()
        val job = register(t1)
        tx(t1) { it.markFailed(job, "no source") }.state shouldBe AuditPackState.FAILED
        val before = rowText(t1)
        val failed = tx(t1) { it.find(TenantId(t1), "E1", q1, job.packId) }!!
        failed.failureReason shouldBe "no source"
        tx(t1) { it.markReady(job, "s3://p/3", listOf("A")) } shouldBe failed
        rowText(t1) shouldBe before
    }

    @Test
    fun `INV-AC-24320 a direct UPDATE of a READY row by the runtime login is refused with CS-22`() {
        val t1 = UUID.randomUUID()
        val job = register(t1)
        tx(t1) { it.markReady(job, "s3://p/1", listOf("A")) }
        val before = rowText(t1)
        refused(t1, "update evidence.audit_pack_job set download_uri = 'x'").message!! shouldContain "CS-22"
        refused(t1, "update evidence.audit_pack_job set state = 'FAILED'").message!! shouldContain "CS-22"
        rowText(t1) shouldBe before
    }

    @Test
    fun `INV-AC-24320 an UPDATE of entity_ref or requested_by is refused`() {
        val t1 = UUID.randomUUID()
        register(t1)
        listOf("entity_ref = 'E2'", "requested_by = 'U2'", "period_ref = 'x'", "tenant_id = '${UUID.randomUUID()}'")
            .forEach { set ->
                refused(t1, "update evidence.audit_pack_job set $set").message!! shouldContain "permission denied"
            }
        rowText(t1) shouldContain "|PENDING|U1|"
    }

    @Test
    fun `INV-AC-24320 a DELETE and a TRUNCATE by the runtime login are refused`() {
        val t1 = UUID.randomUUID()
        register(t1)
        refused(t1, "delete from evidence.audit_pack_job").message!! shouldContain "permission denied"
        refused(t1, "truncate evidence.audit_pack_job").message!! shouldContain "permission denied"
        count(t1) shouldBe 1
    }

    @Test
    fun `INV-AC-24320 a state outside PENDING, READY, FAILED is refused`() {
        val t1 = UUID.randomUUID()
        refused(t1, insertSql(t1, state = "DONE")).message!! shouldContain "check"
        refused(t1, insertSql(t1, state = "pending")).message!! shouldContain "check"
        count(t1) shouldBe 0
    }

    @Test
    fun `INV-AC-24320 a blank requested_by is refused`() {
        val t1 = UUID.randomUUID()
        refused(t1, insertSql(t1, by = "")).message!! shouldContain "check"
        count(t1) shouldBe 0
    }

    @Test
    fun `INV-AC-24320 a failure_reason of 501 characters is refused and 500 is accepted`() {
        val t1 = UUID.randomUUID()
        val job = register(t1)
        val e = runCatching { tx(t1) { it.markFailed(job, "x".repeat(501)) } }.exceptionOrNull()
        (e?.cause ?: e)!!.message!! shouldContain "check"
        tx(t1) { it.markFailed(job, "x".repeat(500)) }.state shouldBe AuditPackState.FAILED
    }

    @Test
    fun `INV-AC-24320 T2 reads no row of T1 and find by the packId of T1 under the T2 binding returns null`() {
        val t1 = UUID.randomUUID()
        val t2 = UUID.randomUUID()
        val job = register(t1)
        tx(t2) { it.find(TenantId(t1), "E1", q1, job.packId) } shouldBe null
        tx(t2) { it.find(TenantId(t2), "E1", q1, job.packId) } shouldBe null
        tx(t1) { it.find(TenantId(t1), "E1", q1, job.packId) } shouldBe job
        val seen =
            EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { c ->
                SignedBindings.bind(c, t2)
                c.createStatement().use { s ->
                    s.executeQuery("select count(*) from evidence.audit_pack_job").use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
            }
        seen shouldBe 0
    }

    @Test
    fun `INV-AC-24320 row-level security is enabled and forced and the tenant_isolation policy exists`() {
        val flags =
            EvidenceDb.superuser().use { c ->
                c
                    .createStatement()
                    .use { st ->
                        st
                            .executeQuery(
                                "select c.relrowsecurity || '|' || c.relforcerowsecurity || '|' || " +
                                    "(select count(*) from pg_policy p where p.polrelid = c.oid and " +
                                    "p.polname = 'tenant_isolation') from pg_class c " +
                                    "where c.oid = 'evidence.audit_pack_job'::regclass",
                            ).use { rs ->
                                rs.next()
                                rs.getString(1)
                            }
                    }
            }
        flags shouldBe "true|true|1"
    }
}
