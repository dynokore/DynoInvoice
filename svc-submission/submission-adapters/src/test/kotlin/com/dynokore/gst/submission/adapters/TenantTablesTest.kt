package com.dynokore.gst.submission.adapters

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.util.UUID

/**
 * CS-19 at the database: every table of the schema is tenant data under forced RLS, or declared platform-scoped.
 * Flyway's own history table is not a table of the service.
 */
class TenantTablesTest {
    private class TableFacts(
        rows: java.sql.ResultSet,
    ) {
        val name: String = rows.getString(1)
        private val rowSecurity = rows.getBoolean(2)
        private val forced = rows.getBoolean(3)
        private val tenantNotNull = rows.getBoolean(4)
        private val hasTenant = rows.getBoolean(5)
        private val comment = rows.getString(6)
        private val signedPolicy = rows.getBoolean(7)

        fun finding(): String? =
            when {
                !hasTenant && comment == "platform-scoped: no tenant data" -> null
                !hasTenant -> "$name: no tenant_id and not platform-scoped"
                tenantNotNull && rowSecurity && forced && signedPolicy -> null
                else -> "$name: tenant_id NOT NULL, ENABLE and FORCE ROW LEVEL SECURITY and a bound_tenant() policy"
            }
    }

    private fun tables(): List<TableFacts> =
        SubmissionDb.superuser().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(INSPECT).use { rows ->
                    generateSequence { if (rows.next()) TableFacts(rows) else null }.toList()
                }
            }
        }

    @Test
    fun `every table has a non-null tenant column with forced row level security and the signed binding policy`() {
        tables().mapNotNull { it.finding() }.shouldBeEmpty()
    }

    @Test
    fun `the tables of the first migration exist`() {
        (
            setOf("submission", "submission_attempt", "work_queue", "lane_rate_window", "outbox") -
                tables().map { it.name }.toSet()
        ).shouldBeEmpty()
    }

    private companion object {
        const val INSPECT =
            "select c.relname, c.relrowsecurity, c.relforcerowsecurity, " +
                "exists (select 1 from pg_attribute a where a.attrelid = c.oid and a.attname = 'tenant_id' " +
                "and a.attnotnull and not a.attisdropped), " +
                "exists (select 1 from pg_attribute a where a.attrelid = c.oid and a.attname = 'tenant_id'), " +
                "coalesce(obj_description(c.oid, 'pg_class'), ''), " +
                "exists (select 1 from pg_policy p where p.polrelid = c.oid " +
                "and pg_get_expr(p.polqual, p.polrelid) like '%bound_tenant()%' " +
                "and pg_get_expr(p.polwithcheck, p.polrelid) like '%bound_tenant()%') " +
                "from pg_class c join pg_namespace n on n.oid = c.relnamespace " +
                "where n.nspname = 'submission' and c.relkind in ('r', 'p') " +
                "and c.relname <> 'flyway_schema_history' order by 1"
    }

    private fun runtime(block: (java.sql.Connection) -> Unit) =
        SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).use { connection ->
            connection.autoCommit = false
            block(connection)
        }

    private fun insertSubmission(
        tenant: UUID,
        signature: String = SignedBindings.signed(tenant),
    ) = runtime { connection ->
        SignedBindings.bind(connection, tenant, signature = signature)
        connection
            .prepareStatement(
                "insert into submission.submission (doc_uuid, version, tenant_id, state, entity_ref) " +
                    "values (?, 1, ?, 'READY', 'E-001')",
            ).use {
                it.setString(1, UUID.randomUUID().toString())
                it.setObject(2, tenant)
                it.executeUpdate()
            }
        connection.commit()
    }

    private fun java.sql.Connection.single(statement: java.sql.PreparedStatement): Int =
        statement.use { prepared ->
            prepared.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }

    private fun java.sql.Connection.rowsOf(tenant: UUID): Int =
        single(
            prepareStatement(
                "select count(*) from submission.submission where tenant_id = ?",
            ).apply { setObject(1, tenant) },
        )

    private fun java.sql.Connection.rowsNotOf(tenant: UUID): Int =
        single(
            prepareStatement(
                "select count(*) from submission.submission where tenant_id <> ?",
            ).apply { setObject(1, tenant) },
        )

    private fun java.sql.Connection.allRows(): Int =
        single(prepareStatement("select count(*) from submission.submission"))

    @Test
    fun `a session of tenant A reads no row of tenant B`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        insertSubmission(a)
        insertSubmission(b)
        runtime { connection ->
            SignedBindings.bind(connection, a)
            connection.rowsOf(b) shouldBe 0
            connection.rowsNotOf(a) shouldBe 0
            connection.rowsOf(a) shouldBe 1
        }
    }

    @Test
    fun `a forged binding reads no row and a write under it is refused`() {
        val tenant = UUID.randomUUID()
        insertSubmission(tenant)
        val forged = SignedBindings.signed(UUID.randomUUID())
        runtime { connection ->
            SignedBindings.bind(connection, tenant, signature = forged)
            connection.allRows() shouldBe 0
            connection.rollback()
        }
        shouldThrow<SQLException> { insertSubmission(tenant, signature = forged) }.sqlState shouldBe "42501"
    }
}
