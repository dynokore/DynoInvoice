package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.application.gapreport.StaleRecordTenantSource
import com.dynokore.gst.masterdata.application.gapreport.SweepStaleRecords
import com.dynokore.gst.masterdata.application.imports.DryRunStore
import com.dynokore.platform.serviceruntime.jobs.JobRuntime
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * The tenant reach of the long-command sweep (INV-T-4944; INV-Q-18051 A): the definer function
 * `masterdata.stale_record_tenants`, its privileges, and the sweep per tenant over a real PostgreSQL. The tests sit
 * beside the other database tests of the service because the adapters module has no database fixture.
 */
class StaleRecordTenantsMigrationTest {
    private val tables = listOf("gap_report", "counterparty_import_dry_run", "counterparty_import")

    private fun hash(seed: String): String =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(seed.toByteArray())
            .joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }

    /** One record of [table] for [tenant], in [state], started [ageMinutes] ago. */
    private fun insert(
        table: String,
        tenant: UUID,
        state: String,
        ageMinutes: Long,
    ) = BoundaryFixture.asSuperuser { c ->
        val id = "R-" + UUID.randomUUID().toString().take(8)
        val done = if (state == "RUNNING") "null" else "now()"
        val importOutcome =
            if (state == "SUCCEEDED") "0, 0, '[]'::jsonb, '${hash(id)}'" else "null, null, null, null"
        val common = "'$state', now() - interval '$ageMinutes minutes', $done"
        val outcome =
            when (state) {
                "FAILED" -> "'{\"code\":\"SRT-001\"}'::jsonb"
                else -> "null"
            }
        val sql =
            when (table) {
                "gap_report" -> {
                    "insert into masterdata.gap_report (id, tenant_id, gap_report_id, entity_ref, actor_ref, state, " +
                        "started_at, completed_at, result, error, created_at, updated_at, version) values " +
                        "(gen_random_uuid(), '$tenant', '$id', 'E-001', 'P', $common, " +
                        "${if (state == "SUCCEEDED") "'{}'::jsonb" else "null"}, $outcome, now(), now(), 0)"
                }

                "counterparty_import_dry_run" -> {
                    "insert into masterdata.counterparty_import_dry_run (id, tenant_id, dry_run_id, actor_ref, " +
                        "file_hash, row_count, rows, state, started_at, completed_at, result, error, created_at, " +
                        "updated_at, version) values (gen_random_uuid(), '$tenant', '$id', 'P', '${hash(id)}', 0, " +
                        "'[]', $common, ${if (state == "SUCCEEDED") "'{}'::jsonb" else "null"}, $outcome, " +
                        "now(), now(), 0)"
                }

                else -> {
                    "insert into masterdata.counterparty_import (id, tenant_id, import_id, entity_ref, actor_ref, " +
                        "dry_run_id, file_hash, provided_by, state, started_at, completed_at, applied_count, " +
                        "failed_count, rejected_rows, record_hash, error, created_at, updated_at, version) values " +
                        "(gen_random_uuid(), '$tenant', '$id', 'E-001', 'P', '$id', '${hash(id)}', 'MIGRATED', " +
                        "$common, $importOutcome, " +
                        "$outcome, now(), now(), 0)"
                }
            }
        c.createStatement().executeUpdate(sql)
        id
    }

    private fun Connection.strings(sql: String): List<String> =
        createStatement().use { st ->
            st.executeQuery(sql).use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
        }

    private fun stateOf(
        table: String,
        tenant: UUID,
    ): List<String> =
        BoundaryFixture.asSuperuser {
            it.strings(
                "select state || ':' || (error is not null) from masterdata.$table " +
                    "where tenant_id = '$tenant' order by started_at",
            )
        }

    @Test
    fun `INV-AC-9601 stale_record_tenants returns each tenant with an old RUNNING record once and no other`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val young = UUID.randomUUID()
        val finished = UUID.randomUUID()
        insert("gap_report", a, "RUNNING", OLD)
        insert("counterparty_import_dry_run", a, "RUNNING", OLD)
        insert("counterparty_import", b, "RUNNING", OLD)
        insert("counterparty_import_dry_run", young, "RUNNING", 1)
        insert("gap_report", finished, "SUCCEEDED", OLD)
        insert("counterparty_import", finished, "FAILED", OLD)
        BoundaryFixture.asRuntime { c ->
            c.createStatement().use { st ->
                st.executeQuery("select * from masterdata.stale_record_tenants($CUTOFF)").use { rs ->
                    assertThat(rs.metaData.columnCount).isEqualTo(1)
                    assertThat(rs.metaData.getColumnName(1)).isEqualTo("stale_tenant")
                    assertThat(rs.metaData.getColumnTypeName(1)).isEqualTo("uuid")
                }
            }
            val seen =
                c
                    .strings("select stale_tenant from masterdata.stale_record_tenants($CUTOFF)")
                    .filter { it in setOf(a, b, young, finished).map(UUID::toString) }
            assertThat(seen.sorted()).isEqualTo(listOf(a.toString(), b.toString()).sorted())
        }
    }

    @Test
    fun `INV-AC-9601 stale_record_tenants is a pinned definer function owned by masterdata_sys`() {
        BoundaryFixture.asSuperuser { c ->
            assertThat(
                c.strings(
                    "select p.prosecdef || '|' || pg_get_userbyid(p.proowner) || '|' || " +
                        "array_to_string(p.proconfig, ',') " +
                        "from pg_proc p join pg_namespace n on n.oid = p.pronamespace " +
                        "where n.nspname = 'masterdata' and p.proname = 'stale_record_tenants'",
                ),
            ).isEqualTo(listOf("true|masterdata_sys|search_path=pg_catalog, masterdata, pg_temp"))
            val fn = "'masterdata.stale_record_tenants(timestamptz)'::regprocedure"

            fun execute(role: String) = c.strings("select has_function_privilege('$role', $fn, 'execute')").single()
            assertThat(execute("masterdata_runtime")).isEqualTo("t")
            assertThat(listOf("public", "masterdata_sys", "masterdata_migrate").map(::execute))
                .isEqualTo(listOf("f", "f", "f"))
            tables.forEach { table ->
                fun column(name: String) =
                    c
                        .strings(
                            "select has_column_privilege('masterdata_sys', 'masterdata.$table', '$name', 'select')",
                        ).single()
                assertThat(listOf("tenant_id", "state", "started_at").map(::column)).isEqualTo(listOf("t", "t", "t"))
                val others =
                    c.strings(
                        "select column_name from information_schema.columns where table_schema = 'masterdata' " +
                            "and table_name = '$table' and column_name not in ('tenant_id','state','started_at')",
                    )
                assertThat(others.filter { column(it) == "t" }).isEmpty()
                assertThat(
                    c.strings("select has_table_privilege('masterdata_sys', 'masterdata.$table', 'update')").single(),
                ).isEqualTo("f")
            }
        }
    }

    private fun run(block: (AssertableWebApplicationContext) -> Unit) {
        BoundaryFixture.runner().run { context ->
            assertThat(context).hasNotFailed()
            block(context)
        }
    }

    @Test
    fun `INV-AC-9601 fire of the sweep with no tenant bound finalises the old records of two tenants`() =
        run { context ->
            val first = UUID.randomUUID()
            val second = UUID.randomUUID()
            val third = UUID.randomUUID()
            listOf(first, second).forEach { tenant ->
                tables.forEach {
                    insert(it, tenant, "RUNNING", OLD)
                    insert(it, tenant, "RUNNING", 1)
                }
            }
            tables.forEach { insert(it, third, "RUNNING", 1) }
            val thirdBefore = tables.map { stateOf(it, third) }
            val clock = context.getBean(Clock::class.java)
            val fired = context.getBean(JobRuntime::class.java).fire("long-command-sweep", clock.instant())
            assertThat(fired.values.map { it.toString() }).allSatisfy { assertThat(it).contains("RAN") }
            listOf(first, second).forEach { tenant ->
                tables.forEach { assertThat(stateOf(it, tenant)).isEqualTo(listOf("FAILED:true", "RUNNING:false")) }
            }
            assertThat(tables.map { stateOf(it, third) }).isEqualTo(thirdBefore)
            val finalised =
                BoundaryFixture.asSuperuser { c ->
                    tables.sumOf { table ->
                        c
                            .strings(
                                "select count(*) from masterdata.$table where state = 'FAILED' " +
                                    "and tenant_id in ('$first', '$second')",
                            ).single()
                            .toInt()
                    }
                }
            assertThat(finalised).isEqualTo(SIX)
        }

    @Test
    fun `INV-AC-9601 a failure for one tenant leaves the others finalised and the sweep throws`() =
        run { context ->
            val bad = UUID.randomUUID()
            val good = UUID.randomUUID()
            insert("counterparty_import_dry_run", bad, "RUNNING", OLD)
            insert("counterparty_import_dry_run", good, "RUNNING", OLD)
            val tenants = context.getBean(TenantContext::class.java)
            val store = context.getBean(DryRunStore::class.java)
            val sweep =
                SweepStaleRecords(
                    context.getBean(TransactionRunner::class.java),
                    context.getBean(Clock::class.java),
                    java.time.Duration.ofMinutes(MAX_RUN),
                    context.getBean(StaleRecordTenantSource::class.java),
                    tenants,
                    "svc:masterdata-svc/job:long-command-sweep",
                    listOf { before: Instant ->
                        check(tenants.current() != TenantId(bad)) { "update made to fail" }
                        store.failRunningBefore(before)
                    },
                )
            assertThatThrownBy { sweep.run() }.isInstanceOf(IllegalStateException::class.java)
            assertThat(stateOf("counterparty_import_dry_run", good)).isEqualTo(listOf("FAILED:true"))
            assertThat(stateOf("counterparty_import_dry_run", bad)).isEqualTo(listOf("RUNNING:false"))
        }

    private companion object {
        const val CUTOFF = "now() - interval '15 minutes'"
        const val OLD = 60L
        const val SIX = 6
        const val MAX_RUN = 15L
    }
}
