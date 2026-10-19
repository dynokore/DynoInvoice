package com.dynokore.gst.submission.adapters

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/** CS-19 at the database: every table of the schema is tenant data under forced RLS, or declared platform-scoped. */
class TenantTablesTest {
    private val inspect =
        """
        select c.relname, c.relrowsecurity, c.relforcerowsecurity,
               exists (select 1 from pg_attribute a where a.attrelid = c.oid and a.attname = 'tenant_id' and a.attnotnull
                       and not a.attisdropped),
               exists (select 1 from pg_attribute a where a.attrelid = c.oid and a.attname = 'tenant_id'),
               coalesce(obj_description(c.oid, 'pg_class'), ''),
               exists (select 1 from pg_policy p where p.polrelid = c.oid
                       and pg_get_expr(p.polqual, p.polrelid) like '%bound_tenant()%'
                       and pg_get_expr(p.polwithcheck, p.polrelid) like '%bound_tenant()%')
        from pg_class c join pg_namespace n on n.oid = c.relnamespace
        where n.nspname = 'submission' and c.relkind in ('r', 'p') order by 1
        """.trimIndent()

    @Test
    fun `every table has a non-null tenant column with forced row level security and the signed binding policy`() {
        val findings = mutableListOf<String>()
        SubmissionDb.superuser().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(inspect).use { rows ->
                    while (rows.next()) {
                        val table = rows.getString(1)
                        val hasColumn = rows.getBoolean(5)
                        if (!hasColumn) {
                            if (rows.getString(6) != "platform-scoped: no tenant data") findings += "$table: no tenant_id and not platform-scoped"
                        } else if (!rows.getBoolean(4) || !rows.getBoolean(2) || !rows.getBoolean(3) || !rows.getBoolean(7)) {
                            findings += "$table: tenant_id NOT NULL, ENABLE and FORCE ROW LEVEL SECURITY and a bound_tenant() policy are required"
                        }
                    }
                }
            }
        }
        findings.shouldBeEmpty()
    }

    @Test
    fun `the tables of the first migration exist`() {
        val tables = mutableSetOf<String>()
        SubmissionDb.superuser().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(inspect).use { rows -> while (rows.next()) tables += rows.getString(1) }
            }
        }
        (setOf("submission", "submission_attempt", "work_queue", "lane_rate_window", "outbox") - tables) shouldBe emptySet()
    }

    @Test
    fun `a session of tenant A reads no row of tenant B`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        listOf(a, b).forEach { tenant ->
            SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).use { connection ->
                connection.autoCommit = false
                SignedBindings.bind(connection, tenant)
                connection.prepareStatement("insert into submission.submission (id, tenant_id, state) values (?, ?, 'NEW')").use {
                    it.setObject(1, UUID.randomUUID())
                    it.setObject(2, tenant)
                    it.executeUpdate()
                }
                connection.commit()
            }
        }
        SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).use { connection ->
            connection.autoCommit = false
            SignedBindings.bind(connection, a)
            fun count(sql: String): Int =
                connection.prepareStatement(sql).use { statement ->
                    statement.setObject(1, b)
                    statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
                }
            count("select count(*) from submission.submission where tenant_id = ?") shouldBe 0
            connection.createStatement().use { statement ->
                statement.executeQuery("select count(*) from submission.submission where tenant_id <> '$a'").use { rows ->
                    rows.next()
                    rows.getInt(1) shouldBe 0
                }
            }
        }
    }

    @Test
    fun `an unsigned or forged binding reads no row`() {
        val tenant = UUID.randomUUID()
        SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).use { connection ->
            connection.autoCommit = false
            SignedBindings.bind(connection, tenant)
            connection.prepareStatement("insert into submission.submission (id, tenant_id, state) values (?, ?, 'NEW')").use {
                it.setObject(1, UUID.randomUUID())
                it.setObject(2, tenant)
                it.executeUpdate()
            }
            connection.commit()
        }
        SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).use { connection ->
            connection.autoCommit = false
            SignedBindings.bind(connection, tenant, signature = SignedBindings.signed(UUID.randomUUID()))
            connection.createStatement().use { statement ->
                statement.executeQuery("select count(*) from submission.submission").use { rows ->
                    rows.next()
                    rows.getInt(1) shouldBe 0
                }
            }
            connection.rollback()
            connection.createStatement().use { statement ->
                statement.executeQuery("select count(*) from submission.submission").use { rows ->
                    rows.next()
                    rows.getInt(1) shouldBe 0
                }
            }
        }
    }
}
