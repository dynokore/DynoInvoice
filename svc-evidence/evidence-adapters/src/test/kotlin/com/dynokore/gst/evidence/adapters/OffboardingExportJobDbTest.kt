package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.persistence.JooqOffboardingExportJobs
import com.dynokore.gst.evidence.application.export.ExportJob
import com.dynokore.gst.evidence.application.export.ExportRequest
import com.dynokore.gst.evidence.application.export.ExportScope
import com.dynokore.gst.evidence.application.export.ExportState
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** INV-AC-22947: the offboarding export job table and its jOOQ store. */
class OffboardingExportJobDbTest {
    private val stated = Instant.parse("2026-11-15T08:30:00Z")
    private val clock = Clock.fixed(stated, ZoneOffset.UTC)

    private fun <T> tx(
        tenant: UUID,
        block: (JooqOffboardingExportJobs) -> T,
    ): T =
        EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, tenant)
            val dsl: DSLContext = DSL.using(c, SQLDialect.POSTGRES)
            block(JooqOffboardingExportJobs(dsl, clock)).also { c.commit() }
        }

    private fun count(tenant: UUID): Int =
        EvidenceDb.superuser().use { c ->
            c.prepareStatement("select count(*) from evidence.offboarding_export_job where tenant_id = ?").use {
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
                    "select state || '|' || coalesce(finished_at::text, '') || '|' || " +
                        "coalesce(manifest_uri, '') || '|' || coalesce(item_count::text, '') || '|' || " +
                        "coalesce(failure_reason, '') " +
                        "from evidence.offboarding_export_job where tenant_id = ?",
                ).use {
                    it.setObject(1, tenant)
                    it.executeQuery().use { rs ->
                        rs.next()
                        rs.getString(1)
                    }
                }
        }

    private fun request(tenant: UUID) =
        ExportRequest(TenantId(tenant), "OFF-1", "EXP-1", ExportScope.TENANT, null, "EV-1")

    private fun register(tenant: UUID): ExportJob = tx(tenant) { it.register(request(tenant)) }

    private fun columns(
        tenant: UUID,
        offboardingRef: String,
    ): List<String?> =
        EvidenceDb.superuser().use { c ->
            c
                .prepareStatement(
                    "select export_ref, scope, entity_ref, requested_event_id " +
                        "from evidence.offboarding_export_job where tenant_id = ? and offboarding_ref = ?",
                ).use {
                    it.setObject(1, tenant)
                    it.setString(2, offboardingRef)
                    it.executeQuery().use { rs ->
                        rs.next()
                        (1..4).map(rs::getString)
                    }
                }
        }

    @Test
    fun `INV-AC-22947 the second register for the same offboarding returns the first row and creates none`() {
        val t1 = UUID.randomUUID()
        val first = register(t1)
        val second = register(t1)
        first.state shouldBe ExportState.REQUESTED
        second shouldBe first
        count(t1) shouldBe 1
    }

    @Test
    fun `INV-AC-22947 a read bound to T2 sees no row of T1`() {
        val t1 = UUID.randomUUID()
        val t2 = UUID.randomUUID()
        register(t1)
        tx(t2) { it.find(TenantId(t1), "OFF-1") } shouldBe null
        tx(t2) { it.find(TenantId(t2), "OFF-1") } shouldBe null
    }

    @Test
    fun `INV-AC-22947 READY stores the manifest, the count, the contents and the stated finishedAt`() {
        val t1 = UUID.randomUUID()
        val job = register(t1)
        val done = tx(t1) { it.markReady(job, "s3://m/1", 7, listOf("A", "B")) }
        done.state shouldBe ExportState.READY
        done.manifestRef shouldBe "s3://m/1"
        done.itemCount shouldBe 7
        done.contents shouldBe listOf("A", "B")
        tx(t1) { it.find(TenantId(t1), "OFF-1") } shouldBe done
        EvidenceDb.superuser().use { c ->
            c.prepareStatement("select finished_at from evidence.offboarding_export_job where tenant_id = ?").use {
                it.setObject(1, t1)
                it.executeQuery().use { rs ->
                    rs.next()
                    rs.getObject(1, java.time.OffsetDateTime::class.java).toInstant() shouldBe stated
                }
            }
        }
    }

    private fun stored(tenant: UUID): ExportJob = tx(tenant) { it.find(TenantId(tenant), "OFF-1") }!!

    @Test
    fun `INV-AC-22947 a READY job moves no more and a FAILED job stays FAILED with the row unchanged`() {
        val t1 = UUID.randomUUID()
        val job = register(t1)
        tx(t1) { it.markReady(job, "s3://m/1", 7, listOf("A")) }
        val before = stored(t1)
        val beforeRow = rowText(t1)
        // the argument job is stale (REQUESTED): the returned job must be the stored one, not the argument
        tx(t1) { it.markReady(job, "s3://m/2", 9, listOf("Z")) } shouldBe before
        tx(t1) { it.markFailed(job, "late") } shouldBe before
        rowText(t1) shouldBe beforeRow

        val t2 = UUID.randomUUID()
        val j2 = register(t2)
        tx(t2) { it.markFailed(j2, "no source") }.state shouldBe ExportState.FAILED
        val failed = stored(t2)
        val failedRow = rowText(t2)
        tx(t2) { it.markReady(j2, "s3://m/3", 1, listOf("A")) } shouldBe failed
        rowText(t2) shouldBe failedRow
    }

    @Test
    fun `INV-AC-23074 the row holds the request export ref, scope, entity ref and event id`() {
        val t1 = UUID.randomUUID()
        val job = register(t1)
        columns(t1, "OFF-1") shouldBe listOf("EXP-1", "TENANT", null, "EV-1")
        job.exportRef shouldBe "EXP-1"
    }

    @Test
    fun `INV-AC-23074 the second register returns the first job and the row keeps EXP-1 and EV-1`() {
        val t1 = UUID.randomUUID()
        val first = register(t1)
        val second = tx(t1) { it.register(request(t1).copy(exportRef = "EXP-2", requestedEventId = "EV-2")) }
        second shouldBe first
        columns(t1, "OFF-1") shouldBe listOf("EXP-1", "TENANT", null, "EV-1")
        count(t1) shouldBe 1
    }

    @Test
    fun `INV-AC-23074 an ENTITY request stores scope ENTITY and its entity ref`() {
        val t1 = UUID.randomUUID()
        tx(t1) {
            it.register(
                request(t1).copy(
                    offboardingRef = "OFF-2",
                    exportRef = "EXP-3",
                    scope = ExportScope.ENTITY,
                    entityRef = "E1",
                    requestedEventId = "EV-3",
                ),
            )
        }
        columns(t1, "OFF-2") shouldBe listOf("EXP-3", "ENTITY", "E1", "EV-3")
    }

    @Test
    fun `INV-AC-23074 the request constructor refuses the four malformed requests`() {
        val t1 = UUID.randomUUID()
        shouldThrow<IllegalArgumentException> { request(t1).copy(scope = ExportScope.ENTITY) }
        shouldThrow<IllegalArgumentException> { request(t1).copy(scope = ExportScope.ENTITY, entityRef = " ") }
        shouldThrow<IllegalArgumentException> { request(t1).copy(entityRef = "E1") }
        shouldThrow<IllegalArgumentException> { request(t1).copy(exportRef = " ") }
        shouldThrow<IllegalArgumentException> { request(t1).copy(requestedEventId = "") }
    }

    @Test
    fun `INV-AC-22947 the trigger refuses a direct update of a terminal row`() {
        val t1 = UUID.randomUUID()
        val job = register(t1)
        tx(t1) { it.markFailed(job, "x") }
        val e = refused(t1, "update evidence.offboarding_export_job set state = 'READY'")
        e.message!! shouldContain "terminal"
    }

    @Test
    fun `INV-AC-22947 a delete by the runtime login is refused`() {
        val t1 = UUID.randomUUID()
        register(t1)
        val e = refused(t1, "delete from evidence.offboarding_export_job")
        e.message!! shouldContain "permission denied"
        count(t1) shouldBe 1
    }

    @Test
    fun `INV-AC-22947 the tenant column is not updatable by the runtime login`() {
        val t1 = UUID.randomUUID()
        register(t1)
        val e = refused(t1, "update evidence.offboarding_export_job set tenant_id = '${UUID.randomUUID()}'")
        e.message!! shouldContain "permission denied"
    }

    @Test
    fun `INV-AC-22947 a failure reason of 501 characters is refused by the database`() {
        val t1 = UUID.randomUUID()
        val job = register(t1)
        val e = runCatching { tx(t1) { it.markFailed(job, "x".repeat(501)) } }.exceptionOrNull()
        (e?.cause ?: e)!!.message!! shouldContain "check"
        tx(t1) { it.markFailed(job, "x".repeat(500)) }.state shouldBe ExportState.FAILED
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
}
