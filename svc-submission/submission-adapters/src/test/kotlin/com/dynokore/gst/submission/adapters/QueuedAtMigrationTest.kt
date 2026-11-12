package com.dynokore.gst.submission.adapters

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.Connection
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

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.strings(sql: String): List<String?> =
        createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
            }
        }

    private fun backfillSql(): String {
        val text =
            checkNotNull(javaClass.getResourceAsStream("/db/migration/V202611161300__submission_queued_at.sql")) {
                "migration resource missing"
            }.use { String(it.readBytes(), Charsets.UTF_8) }
        check(Regex("(?m)^-- backfill:begin$").findAll(text).count() == 1) { "backfill:begin must occur once" }
        check(Regex("(?m)^-- backfill:end$").findAll(text).count() == 1) { "backfill:end must occur once" }
        return text.substring(text.indexOf("-- backfill:begin") + 17, text.indexOf("-- backfill:end"))
    }

    private fun queue(
        c: Connection,
        tenant: UUID,
        doc: String,
        version: Int,
        at: String,
    ) = c.exec(
        "insert into submission.work_queue (tenant_id, partition_key, lane, payload, enqueued_at) values " +
            "('$tenant', '$doc', 'LIVE', '{\"docUuid\":\"$doc\",\"version\":$version}'::jsonb, '$at')",
    )

    private fun seed(
        c: Connection,
        tenant: UUID,
        other: UUID,
        docs: Triple<String, String, String>,
    ) {
        val (withAttempt, queueOnly, neither) = docs
        listOf(withAttempt, queueOnly, neither).forEach {
            c.exec(
                "insert into submission.submission (tenant_id, doc_uuid, version, state, entity_ref) " +
                    "values ('$tenant', '$it', 1, 'QUEUED', 'E-001')",
            )
        }
        listOf(2, 1).forEach { n ->
            c.exec(
                "insert into submission.submission_attempt (tenant_id, doc_uuid, version, attempt_number, " +
                    "correlation_id, requested_at) values ('$tenant', '$withAttempt', 1, $n, " +
                    "'${UUID.randomUUID()}', '2026-11-01T0$n:00:00Z')",
            )
        }
        queue(c, tenant, withAttempt, 1, "2026-10-01T00:00:00Z")
        queue(c, tenant, queueOnly, 1, "2026-11-02T05:00:00Z")
        queue(c, tenant, queueOnly, 1, "2026-11-02T04:00:00Z")
        // look-alikes: another version, another tenant
        queue(c, tenant, queueOnly, 2, "2026-01-01T00:00:00Z")
        queue(c, other, queueOnly, 1, "2026-01-01T00:00:00Z")
        queue(c, tenant, neither, 2, "2026-01-01T00:00:00Z")
    }

    @Test
    fun `the backfill uses the earliest attempt, else the earliest matching queue row, else stays NULL`() {
        val tenant = UUID.randomUUID()
        val other = UUID.randomUUID()
        val (withAttempt, queueOnly, neither) = List(3) { UUID.randomUUID().toString() }
        SubmissionDb.superuser().use { c ->
            try {
                seed(c, tenant, other, Triple(withAttempt, queueOnly, neither))
                SubmissionDb.connect(SubmissionDb.MIGRATE, SubmissionDb.MIGRATE_PASSWORD).use { m ->
                    m.createStatement().use { it.execute(backfillSql()) }
                }
                val at = { d: String ->
                    c.strings(
                        "select coalesce(queued_at::text, 'null') from submission.submission where doc_uuid = '$d'",
                    )
                }
                at(withAttempt) shouldBe c.strings("select '2026-11-01T01:00:00Z'::timestamptz::text")
                at(queueOnly) shouldBe c.strings("select '2026-11-02T04:00:00Z'::timestamptz::text")
                at(neither) shouldBe listOf("null")
                c.strings(
                    "select relname || ':' || relforcerowsecurity from pg_class " +
                        "where relnamespace = 'submission'::regnamespace " +
                        "and relname in ('submission', 'submission_attempt', 'work_queue') order by 1",
                ) shouldBe listOf("submission:true", "submission_attempt:true", "work_queue:true")
            } finally {
                val docs = "'$withAttempt','$queueOnly','$neither'"
                c.exec("delete from submission.submission_attempt where doc_uuid in ($docs)")
                c.exec("delete from submission.work_queue where partition_key in ($docs)")
                c.exec("delete from submission.submission where doc_uuid in ($docs)")
            }
        }
    }
}
