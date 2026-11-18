package com.dynokore.gst.reconciliation.adapters

import com.dynokore.gst.reconciliation.adapters.persistence.JooqRunAttestationWriter
import com.dynokore.gst.reconciliation.adapters.persistence.JooqRunAttestations
import com.dynokore.gst.reconciliation.adapters.persistence.JooqRunPreparers
import com.dynokore.gst.reconciliation.application.workpaper.SoleAttester
import com.dynokore.gst.reconciliation.application.workpaper.StoredAttestation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit

/** INV-AC-24434 (INV-T-24435): the attestation writer, the attestation read and the preparer read, over PostgreSQL. */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class AttestationAdaptersDbTest : RunStoreDbFixture() {
    private val u2At = Instant.parse("2026-04-28T09:30:00Z")
    private val u1At = Instant.parse("2026-04-29T10:00:00Z")

    private fun superuserExecute(
        sql: String,
        vararg args: Any?,
    ) = ReconciliationDb.superuser().use { c ->
        c.prepareStatement(sql).use {
            args.forEachIndexed { i, a -> it.setObject(i + 1, a) }
            it.executeUpdate()
        }
    }

    private fun returnRow(
        tenant: UUID,
        version: Int,
        uploadedBy: String,
        preparer: String? = "free-text-preparer",
    ) = superuserExecute(
        "insert into reconciliation.gst_return (tenant_id, entity_ref, period_ref, version, form, filed_at, " +
            "filing_acknowledgement, preparer, uploaded_by, recorded_at) " +
            "values (?, 'E1', '2026-Q1', ?, 'F5', now(), 'ACK', ?, ?, now())",
        tenant,
        version,
        preparer,
        uploadedBy,
    )

    private fun runRow(
        tenant: UUID,
        run: UUID,
        version: Int,
    ) = superuserExecute(
        "insert into reconciliation.reconciliation_run (tenant_id, run_id, entity_ref, period_ref, return_version, " +
            "return_form, completed_at, unexplained_residual, raises_per003) " +
            "values (?, ?, 'E1', '2026-Q1', ?, 'F5', now(), 0, false)",
        tenant,
        run,
        version,
    )

    private fun insert(
        tenant: UUID,
        run: UUID,
        stored: StoredAttestation,
    ): Boolean =
        asTenant(tenant) {
            session.runner.inTransaction {
                JooqRunAttestationWriter(session.dsl()).insert(tenant.toString(), run.toString(), stored)
            }
        }

    private fun get(
        tenant: UUID,
        run: UUID,
    ): StoredAttestation? =
        asTenant(tenant) {
            session.runner.inTransaction { JooqRunAttestations(session.dsl()).get(tenant.toString(), run.toString()) }
        }

    private fun preparer(
        tenant: UUID,
        run: UUID,
    ): String? =
        asTenant(tenant) {
            session.runner.inTransaction {
                JooqRunPreparers(
                    session.dsl(),
                ).preparerOf(tenant.toString(), run.toString())
            }
        }

    private fun rowText(run: UUID) =
        scalar("select row(a.*)::text from reconciliation.work_paper_attestation a where run_id = '$run'")

    private fun sqlState(block: () -> Unit): String? =
        generateSequence<Throwable>(shouldThrow<Exception> { block() }) { it.cause }
            .filterIsInstance<SQLException>()
            .first()
            .sqlState

    private class World(
        val t1: UUID = UUID.randomUUID(),
        val t2: UUID = UUID.randomUUID(),
        val r1: UUID = UUID.randomUUID(),
        val r2: UUID = UUID.randomUUID(),
        val r3: UUID = UUID.randomUUID(),
    )

    private fun world(): World {
        val w = World()
        returnRow(w.t1, 2, "U1")
        runRow(w.t1, w.r1, 2)
        runRow(w.t1, w.r2, 2)
        runRow(w.t1, w.r3, 2)
        return w
    }

    @Test
    fun `INV-AC-24434 the first insert returns true and the repeat returns false and keeps the first row`() {
        val w = world()
        insert(w.t1, w.r1, StoredAttestation("U2", u2At)) shouldBe true
        val before = rowText(w.r1)
        insert(w.t1, w.r1, StoredAttestation("U3", u1At)) shouldBe false
        scalar("select attested_by from reconciliation.work_paper_attestation where run_id = '${w.r1}'") shouldBe "U2"
        rowText(w.r1) shouldBe before
        scalar("select count(*) from reconciliation.work_paper_attestation where run_id = '${w.r1}'") shouldBe 1L
        scalar(
            "select sole_attester_acknowledged_by is null and sole_attester_acknowledged_on is null " +
                "from reconciliation.work_paper_attestation where run_id = '${w.r1}'",
        ) shouldBe true
    }

    @Test
    fun `INV-AC-24434 a sole-attester insert stores both columns`() {
        val w = world()
        insert(w.t1, w.r2, StoredAttestation("U1", u1At, SoleAttester("U9", LocalDate.parse("2026-03-01")))) shouldBe
            true
        scalar(
            "select sole_attester_acknowledged_by from reconciliation.work_paper_attestation where run_id = '${w.r2}'",
        ) shouldBe "U9"
        scalar(
            "select sole_attester_acknowledged_on::text from reconciliation.work_paper_attestation " +
                "where run_id = '${w.r2}'",
        ) shouldBe "2026-03-01"
        scalar("select tenant_id::text from reconciliation.work_paper_attestation where run_id = '${w.r2}'") shouldBe
            w.t1.toString()
    }

    @Test
    fun `INV-AC-24434 get returns the stored fact, the sole attester, and null for a missing row or another tenant`() {
        val w = world()
        insert(w.t1, w.r1, StoredAttestation("U2", u2At))
        insert(w.t1, w.r2, StoredAttestation("U1", u1At, SoleAttester("U9", LocalDate.parse("2026-03-01"))))
        get(w.t1, w.r1) shouldBe StoredAttestation("U2", u2At, null)
        get(w.t1, w.r2) shouldBe StoredAttestation("U1", u1At, SoleAttester("U9", LocalDate.parse("2026-03-01")))
        get(w.t1, w.r3) shouldBe null
        get(w.t2, w.r1) shouldBe null
    }

    @Test
    fun `INV-AC-24434 get names the tenant itself, so a connection that bypasses row security reads one tenant`() {
        val w = world()
        insert(w.t1, w.r1, StoredAttestation("U2", u2At))
        ReconciliationDb.superuser().use { c ->
            val dsl =
                org.jooq.impl.DSL
                    .using(c, org.jooq.SQLDialect.POSTGRES)
            JooqRunAttestations(dsl).get(w.t2.toString(), w.r1.toString()) shouldBe null
            JooqRunAttestations(dsl).get(w.t1.toString(), w.r1.toString()) shouldBe StoredAttestation("U2", u2At, null)
        }
    }

    @Test
    fun `INV-AC-24434 preparerOf returns the uploaded_by of the return version, not the free-text preparer`() {
        val w = world()
        preparer(w.t1, w.r1) shouldBe "U1"
        preparer(w.t1, w.r1) shouldBe
            scalar("select uploaded_by from reconciliation.gst_return where tenant_id = '${w.t1}'")
        preparer(w.t1, w.r2) shouldBe "U1"
    }

    @Test
    fun `INV-AC-24434 preparerOf joins the version of the run and answers null for a missing return row`() {
        val w = world()
        returnRow(w.t1, 3, "U7")
        val other = UUID.randomUUID()
        runRow(w.t1, other, 3)
        preparer(w.t1, other) shouldBe "U7"
        preparer(w.t1, w.r1) shouldBe "U1"
        val noReturn = UUID.randomUUID()
        runRow(w.t1, noReturn, 9)
        preparer(w.t1, noReturn) shouldBe null
        preparer(w.t1, UUID.randomUUID()) shouldBe null
    }

    @Test
    fun `INV-AC-24434 preparerOf answers null for a run of another tenant and names the tenant itself`() {
        val w = world()
        preparer(w.t2, w.r1) shouldBe null
        ReconciliationDb.superuser().use { c ->
            val dsl =
                org.jooq.impl.DSL
                    .using(c, org.jooq.SQLDialect.POSTGRES)
            JooqRunPreparers(dsl).preparerOf(w.t2.toString(), w.r1.toString()) shouldBe null
            JooqRunPreparers(dsl).preparerOf(w.t1.toString(), w.r1.toString()) shouldBe "U1"
        }
    }

    @Test
    fun `INV-AC-24434 an update and a delete of the attestation row by the runtime login fail with 42501`() {
        val w = world()
        insert(w.t1, w.r1, StoredAttestation("U2", u2At))
        val before = rowText(w.r1)
        runtimeSqlState(w.t1, "update reconciliation.work_paper_attestation set attested_by = 'X'") shouldBe "42501"
        runtimeSqlState(w.t1, "delete from reconciliation.work_paper_attestation") shouldBe "42501"
        rowText(w.r1) shouldBe before
    }

    @Test
    fun `INV-AC-24434 a row with exactly one of the two sole-attester columns set is refused by the table check`() {
        val w = world()
        val by =
            "insert into reconciliation.work_paper_attestation (tenant_id, run_id, attested_by, attested_at, " +
                "sole_attester_acknowledged_by) values (?, ?, 'U2', now(), 'U9')"
        val on =
            "insert into reconciliation.work_paper_attestation (tenant_id, run_id, attested_by, attested_at, " +
                "sole_attester_acknowledged_on) values (?, ?, 'U2', now(), '2026-03-01'::date)"
        sqlState { superuserExecute(by, w.t1, w.r1) } shouldBe "23514"
        sqlState { superuserExecute(on.replace("'2026-03-01'::date", "'2026-03-01'::date"), w.t1, w.r1) } shouldBe
            "23514"
        scalar("select count(*) from reconciliation.work_paper_attestation where run_id = '${w.r1}'") shouldBe 0L
    }
}
