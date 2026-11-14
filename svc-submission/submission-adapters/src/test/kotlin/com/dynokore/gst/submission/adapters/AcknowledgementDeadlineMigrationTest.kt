package com.dynokore.gst.submission.adapters

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.util.UUID

/** Probes of the migration `acknowledgement_deadline` (INV-T-22753; INV-REQ-975; INV-AC-22748). */
class AcknowledgementDeadlineMigrationTest {
    private fun Connection.strings(sql: String): List<String?> =
        createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
            }
        }

    @Test
    fun `INV-AC-22748 the column is timestamptz, nullable, without default and without check`() {
        SubmissionDb.superuser().use { c ->
            c.strings(
                "select data_type || ':' || is_nullable || ':' || coalesce(column_default, 'none') " +
                    "from information_schema.columns where table_schema = 'submission' " +
                    "and table_name = 'submission' and column_name = 'acknowledgement_deadline'",
            ) shouldBe listOf("timestamp with time zone:YES:none")
            c.strings(
                "select count(*)::text from pg_constraint where conrelid = 'submission.submission'::regclass " +
                    "and contype = 'c' and pg_get_constraintdef(oid) like '%acknowledgement_deadline%'",
            ) shouldBe listOf("0")
        }
    }

    @Test
    fun `INV-AC-22748 the column carries the comment class operational`() {
        SubmissionDb.superuser().use { c ->
            c.strings(
                "select col_description('submission.submission'::regclass, a.attnum) from pg_attribute a " +
                    "where a.attrelid = 'submission.submission'::regclass and a.attname = 'acknowledgement_deadline'",
            ) shouldBe listOf("class: operational")
        }
    }

    private fun migrationSql(): String =
        checkNotNull(javaClass.getResourceAsStream("/db/migration/V202611181200__acknowledgement_deadline.sql")) {
            "migration resource missing"
        }.use { String(it.readBytes(), Charsets.UTF_8) }

    /**
     * The state before the migration is rebuilt inside one transaction that is always rolled back: the column is
     * dropped, a version row is inserted, the migration file is executed on the table holding that row, and the row
     * is read. Nothing of this is visible to other connections.
     */
    @Test
    fun `INV-AC-22748 a version row inserted before the migration reads null after it`() {
        val doc = UUID.randomUUID().toString()
        SubmissionDb.superuser().use { c ->
            c.autoCommit = false
            try {
                c.createStatement().use {
                    it.execute("alter table submission.submission drop column acknowledgement_deadline")
                }
                c.createStatement().use {
                    it.execute(
                        "insert into submission.submission (tenant_id, doc_uuid, version, state, entity_ref) " +
                            "values ('${UUID.randomUUID()}', '$doc', 1, 'SENT', 'E-001')",
                    )
                }
                c.createStatement().use { it.execute(migrationSql()) }
                c.strings(
                    "select coalesce(acknowledgement_deadline::text, 'null') from submission.submission " +
                        "where doc_uuid = '$doc'",
                ) shouldBe listOf("null")
            } finally {
                c.rollback()
            }
        }
        SubmissionDb.superuser().use { c ->
            c.strings(
                "select count(*)::text from information_schema.columns where table_schema = 'submission' " +
                    "and table_name = 'submission' and column_name = 'acknowledgement_deadline'",
            ) shouldBe listOf("1")
        }
    }
}
