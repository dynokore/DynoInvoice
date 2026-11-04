package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.persistence.JooqAlertEmissionLedger
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.time.LocalDate
import java.util.UUID

/** Probes of the migration `compliance_tenants_and_alert_ledger` (INV-T-16762; INV-Q-16751 A, INV-Q-16754 A). */
class ComplianceAlertMigrationTest {
    private val connection =
        SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).also { it.autoCommit = false }
    private val dsl = DSL.using(connection, SQLDialect.POSTGRES)
    private val ledger = JooqAlertEmissionLedger(dsl)
    private val created = mutableListOf<UUID>()

    @AfterEach
    fun cleanUp() {
        connection.close()
        SubmissionDb.superuser().use { c ->
            created.forEach { t ->
                c.createStatement().use {
                    it.execute("delete from submission.compliance_alert_ledger where tenant_id = '$t'")
                    it.execute("delete from submission.obligation_register_entry where tenant_id = '$t'")
                }
            }
        }
    }

    private fun tenant() = UUID.randomUUID().also { created += it }

    private fun <T> inTx(
        tenant: UUID,
        commit: Boolean = true,
        block: () -> T,
    ): T {
        SignedBindings.bind(connection, tenant)
        return try {
            block().also { if (commit) connection.commit() }
        } finally {
            if (!commit) connection.rollback()
        }
    }

    private fun claim(
        t: UUID,
        day: LocalDate,
        entity: String = "E1",
        period: String = "P1",
    ) = inTx(t) { ledger.claim(TenantId(t), entity, period, day) }

    private fun Connection.strings(sql: String): List<String> =
        createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
            }
        }

    private fun ownerRows(sql: String) = SubmissionDb.superuser().use { it.strings(sql) }

    private val d = LocalDate.of(2026, 11, 3)

    @Test
    fun `INV-AC-16760 the ledger accepts one claim per day, a later day, refuses an earlier or equal day and isolates tenants`() {
        val t1 = tenant()
        val t2 = tenant()
        listOf(d, d, d.plusDays(1), d).map { claim(t1, it) } shouldBe listOf(true, false, true, false)
        ownerRows("select measured_on from submission.compliance_alert_ledger where tenant_id = '$t1'") shouldBe
            listOf(d.plusDays(1).toString())
        claim(t2, d) shouldBe true
        ownerRows(
            "select count(*) from submission.compliance_alert_ledger where tenant_id in ('$t1', '$t2')",
        ) shouldBe listOf("2")
        inTx(t2) { dsl.fetch("select tenant_id from submission.compliance_alert_ledger").map { it[0].toString() } } shouldBe
            listOf(t2.toString())
        inTx(t1) { dsl.fetch("select tenant_id from submission.compliance_alert_ledger").map { it[0].toString() } } shouldBe
            listOf(t1.toString())
    }

    @Test
    fun `INV-AC-16760 a second period keeps its own row`() {
        val t = tenant()
        claim(t, d, period = "P1") shouldBe true
        claim(t, d, period = "P2") shouldBe true
        claim(t, d, period = "P1") shouldBe false
        ownerRows("select count(*) from submission.compliance_alert_ledger where tenant_id = '$t'") shouldBe listOf("2")
    }

    @Test
    fun `INV-AC-16760 a claim in a rolled back transaction leaves no row`() {
        val t = tenant()
        inTx(t, commit = false) { ledger.claim(TenantId(t), "E1", "P1", d) } shouldBe true
        ownerRows("select count(*) from submission.compliance_alert_ledger where tenant_id = '$t'") shouldBe listOf("0")
        claim(t, d) shouldBe true
    }

    @Test
    fun `compliance_tenants returns each tenant with an in-scope entry once and no out-of-scope-only tenant`() {
        val both = tenant()
        val onlyOut = tenant()
        SubmissionDb.superuser().use { c ->
            listOf(Triple(both, "a1", true), Triple(both, "a2", true), Triple(both, "a3", false), Triple(onlyOut, "b1", false))
                .forEach { (t, doc, scope) ->
                    c.createStatement().use {
                        it.execute(
                            "insert into submission.obligation_register_entry " +
                                "(tenant_id, doc_uuid, entity_ref, period_ref, due_date, in_scope, kind) " +
                                "values ('$t', '$t-$doc', 'E1', 'P1', '2026-11-30', $scope, 'INVOICE')",
                        )
                    }
                }
        }
        SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).use { r ->
            r.createStatement().use { st ->
                st.executeQuery("select * from submission.compliance_tenants()").use { rs ->
                    rs.metaData.columnCount shouldBe 1
                    rs.metaData.getColumnName(1) shouldBe "compliance_tenant"
                    rs.metaData.getColumnTypeName(1) shouldBe "uuid"
                }
            }
            val seen = r.strings("select compliance_tenant from submission.compliance_tenants()")
            seen.count { it == both.toString() } shouldBe 1
            (onlyOut.toString() in seen) shouldBe false
        }
    }

    @Test
    fun `the runtime role cannot read the ledger of another tenant and has no delete privilege`() {
        val t1 = tenant()
        val t2 = tenant()
        claim(t1, d) shouldBe true
        inTx(t2) {
            dsl.fetch("select 1 from submission.compliance_alert_ledger where tenant_id = '$t1'").size shouldBe 0
        }
        ownerRows(
            "select has_table_privilege('submission_runtime', 'submission.compliance_alert_ledger', 'delete')",
        ) shouldBe listOf("f")
        ownerRows(
            "select has_table_privilege('submission_runtime', 'submission.compliance_alert_ledger', 'select,insert,update')",
        ) shouldBe listOf("t")
    }

    @Test
    fun `compliance_tenants is security definer owned by submission_sys and executable by the runtime role only`() {
        ownerRows(
            "select p.prosecdef || '|' || pg_get_userbyid(p.proowner) || '|' || array_to_string(p.proconfig, ',') " +
                "|| '|' || has_function_privilege('submission_runtime', p.oid, 'execute') " +
                "|| '|' || has_function_privilege('public', p.oid, 'execute') " +
                "from pg_proc p join pg_namespace n on n.oid = p.pronamespace " +
                "where n.nspname = 'submission' and p.proname = 'compliance_tenants'",
        ) shouldBe listOf("true|submission_sys|search_path=pg_catalog, submission, pg_temp|true|false")
        SubmissionDb.superuser().use { c ->
            c.createStatement().use {
                it.execute("drop role if exists compliance_probe")
                it.execute("create role compliance_probe")
            }
            try {
                c.strings(
                    "select has_function_privilege('compliance_probe', 'submission.compliance_tenants()', 'execute')",
                ) shouldBe listOf("f")
            } finally {
                c.createStatement().use { it.execute("drop role compliance_probe") }
            }
        }
    }

    @Test
    fun `the runtime role fails to delete from the ledger`() {
        val t = tenant()
        claim(t, d) shouldBe true
        val failed =
            try {
                inTx(t) { dsl.execute("delete from submission.compliance_alert_ledger") }
                false
            } catch (_: Exception) {
                connection.rollback()
                true
            }
        failed shouldBe true
    }
}
