package com.dynokore.gst.submission.adapters

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/** Probes of the migration `submission_queued_at` (INV-T-20582, INV-REQ-1370). */
class QueuedAtMigrationTest {
    private fun scalar(sql: String): String? =
        SubmissionDb.superuser().use { c ->
            c.createStatement().use { st ->
                st.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    private val columnWhere =
        "from information_schema.columns where table_schema = 'submission' and table_name = 'submission' " +
            "and column_name = 'queued_at'"

    @Test
    fun `the column queued_at exists as a timestamptz`() {
        scalar("select data_type $columnWhere") shouldBe "timestamp with time zone"
    }

    @Test
    fun `the column queued_at is nullable and has no default`() {
        scalar("select is_nullable $columnWhere") shouldBe "YES"
        scalar("select column_default $columnWhere") shouldBe null
    }

    @Test
    fun `the column queued_at carries the class comment operational`() {
        scalar(
            "select col_description('submission.submission'::regclass, attnum) from pg_attribute " +
                "where attrelid = 'submission.submission'::regclass and attname = 'queued_at'",
        ) shouldBe
            "class: operational"
    }

    @Test
    fun `a row inserted without queued_at keeps NULL`() {
        val doc = UUID.randomUUID().toString()
        SubmissionDb.superuser().use { c ->
            c.createStatement().use {
                it.execute(
                    "insert into submission.submission (tenant_id, doc_uuid, version, state, entity_ref) " +
                        "values ('${UUID.randomUUID()}', '$doc', 1, 'READY', 'E-001')",
                )
            }
            val nulls =
                c.createStatement().use { st ->
                    st.executeQuery("select queued_at is null from submission.submission where doc_uuid = '$doc'").use {
                        it.next()
                        it.getBoolean(1)
                    }
                }
            c.createStatement().use { it.execute("delete from submission.submission where doc_uuid = '$doc'") }
            nulls shouldBe true
        }
    }
}
