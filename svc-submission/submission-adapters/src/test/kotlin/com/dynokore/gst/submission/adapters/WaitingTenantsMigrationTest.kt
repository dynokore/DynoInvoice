package com.dynokore.gst.submission.adapters

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.util.UUID

/** Probes of the migration `waiting_tenants` (INV-T-7319; INV-Q-15333 A). */
class WaitingTenantsMigrationTest {
    private class Row(
        val tenant: UUID,
        val doc: UUID,
        val version: Int,
        val state: String,
    )

    private fun insert(
        c: Connection,
        r: Row,
    ) = c.createStatement().use {
        it.execute(
            "insert into submission.submission (tenant_id, doc_uuid, version, state, entity_ref) " +
                "values ('${r.tenant}', '${r.doc}', ${r.version}, '${r.state}', 'E-001')",
        )
    }

    private fun Connection.strings(sql: String): List<String> =
        createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
            }
        }

    private fun <T> withRows(
        rows: List<Row>,
        block: (Connection) -> T,
    ): T {
        SubmissionDb.superuser().use { c -> rows.forEach { insert(c, it) } }
        try {
            return SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).use(block)
        } finally {
            SubmissionDb.superuser().use { c ->
                rows.map { it.doc }.distinct().forEach {
                    c.createStatement().use { st -> st.execute("delete from submission.submission where doc_uuid = '$it'") }
                }
            }
        }
    }

    private fun u() = UUID.randomUUID()

    @Test
    fun `waiting_tenants returns each waiting tenant once and respects tenant and Doc UUID`() {
        val (a, b, c, d) = List(4) { u() }
        val (a1, a2, b1, c1, d1, shared) = List(6) { u() }
        val other = u()
        val rows =
            listOf(
                Row(a, a1, 1, "ACKNOWLEDGED"),
                Row(a, a1, 2, "READY"),
                Row(a, a2, 1, "ACKNOWLEDGED"),
                Row(a, a2, 2, "READY"),
                Row(b, b1, 1, "RETRY"),
                Row(b, b1, 2, "READY"),
                Row(c, c1, 1, "QUEUED"),
                Row(d, d1, 1, "READY"),
                Row(other, shared, 1, "RETRY"),
                Row(a, shared, 2, "READY"),
            )
        withRows(rows) { runtime ->
            val seen = runtime.strings("select waiting_tenant from submission.waiting_tenants()")
            seen.count { it == a.toString() } shouldBe 1
            (d.toString() in seen) shouldBe true
            (b.toString() in seen) shouldBe false
            (c.toString() in seen) shouldBe false
        }
    }

    @Test
    fun `waiting_tenants is security definer owned by submission_sys with a pinned search_path and one column`() {
        SubmissionDb.superuser().use { c ->
            c.strings(
                "select p.prosecdef || '|' || pg_get_userbyid(p.proowner) || '|' || array_to_string(p.proconfig, ',') " +
                    "|| '|' || has_function_privilege('submission_runtime', p.oid, 'execute') " +
                    "|| '|' || has_function_privilege('public', p.oid, 'execute') " +
                    "from pg_proc p join pg_namespace n on n.oid = p.pronamespace " +
                    "where n.nspname = 'submission' and p.proname = 'waiting_tenants'",
            ) shouldBe listOf("true|submission_sys|search_path=pg_catalog, submission, pg_temp|true|false")
        }
        SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).use { r ->
            r.createStatement().use { st ->
                st.executeQuery("select * from submission.waiting_tenants()").use { rs ->
                    rs.metaData.columnCount shouldBe 1
                    rs.metaData.getColumnName(1) shouldBe "waiting_tenant"
                    rs.metaData.getColumnTypeName(1) shouldBe "uuid"
                }
            }
        }
    }
}
