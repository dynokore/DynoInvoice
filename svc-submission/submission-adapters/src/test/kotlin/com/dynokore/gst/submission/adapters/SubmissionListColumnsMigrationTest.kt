package com.dynokore.gst.submission.adapters

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

/** Probes of the migration `submission_list_columns` (INV-T-19478; INV-REQ-975; INV-AC-19464). */
class SubmissionListColumnsMigrationTest {
    private class Seeded(
        val tenantA: UUID = UUID.randomUUID(),
        val tenantB: UUID = UUID.randomUUID(),
        val docs: List<String> = List(6) { UUID.randomUUID().toString() },
    )

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.strings(sql: String): List<String?> =
        createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
            }
        }

    private fun sqlState(block: () -> Unit): String? = (runCatching(block).exceptionOrNull() as? SQLException)?.sqlState

    private fun insertVersion(
        c: Connection,
        tenant: UUID,
        doc: String,
        ackTimer: Boolean,
    ) = c.exec(
        "insert into submission.submission (tenant_id, doc_uuid, version, state, entity_ref, ack_timer_started_at) " +
            "values ('$tenant', '$doc', 1, 'SENT', 'E-001', ${if (ackTimer) "'2026-11-01T08:00:00Z'" else "null"})",
    )

    private fun insertAttempt(
        c: Connection,
        tenant: UUID,
        doc: String,
        number: Int,
        respondedAt: String,
    ) = c.exec(
        "insert into submission.submission_attempt (tenant_id, doc_uuid, version, attempt_number, correlation_id, " +
            "requested_at, responded_at) values ('$tenant', '$doc', 1, $number, '${UUID.randomUUID()}', " +
            "'2026-11-01T08:00:00Z', '$respondedAt')",
    )

    /** Per tenant: docs[i] ack timer with 2 attempts, docs[i+1] attempts only, docs[i+2] ack timer only. */
    private fun seed(
        c: Connection,
        s: Seeded,
    ) {
        listOf(s.tenantA to 0, s.tenantB to 3).forEach { (tenant, i) ->
            val (full, noTimer, noAttempt) = s.docs.subList(i, i + 3)
            insertVersion(c, tenant, full, true)
            insertVersion(c, tenant, noTimer, false)
            insertVersion(c, tenant, noAttempt, true)
            insertAttempt(c, tenant, full, 1, FIRST)
            insertAttempt(c, tenant, full, 2, SECOND)
            insertAttempt(c, tenant, noTimer, 1, FIRST)
            insertAttempt(c, tenant, noTimer, 2, SECOND)
        }
    }

    private fun clean(
        c: Connection,
        s: Seeded,
    ) {
        val list = s.docs.joinToString(",") { "'$it'" }
        c.exec("delete from submission.submission_attempt where doc_uuid in ($list)")
        c.exec("delete from submission.submission where doc_uuid in ($list)")
    }

    private fun backfillSql(): String {
        val text =
            checkNotNull(javaClass.getResourceAsStream("/db/migration/V202611101000__submission_list_columns.sql")) {
                "migration resource missing"
            }.use { String(it.readBytes(), Charsets.UTF_8) }
        check(Regex("(?m)^-- backfill:begin$").findAll(text).count() == 1) { "backfill:begin must occur once" }
        check(Regex("(?m)^-- backfill:end$").findAll(text).count() == 1) { "backfill:end must occur once" }
        val begin = text.indexOf("-- backfill:begin")
        val end = text.indexOf("-- backfill:end")
        check(begin < end) { "markers out of order" }
        return text.substring(begin + "-- backfill:begin".length, end)
    }

    private fun runBackfill() =
        SubmissionDb.connect(SubmissionDb.MIGRATE, SubmissionDb.MIGRATE_PASSWORD).use { m ->
            m.createStatement().use { it.execute(backfillSql()) }
        }

    private fun <T> withSeed(block: (Connection, Seeded) -> T): T {
        val s = Seeded()
        SubmissionDb.superuser().use { seed(it, s) }
        try {
            return SubmissionDb.superuser().use { block(it, s) }
        } finally {
            SubmissionDb.superuser().use { clean(it, s) }
        }
    }

    @Test
    fun `INV-AC-19464 the three columns exist, are nullable and have the stated types`() {
        SubmissionDb.superuser().use { c ->
            val rows =
                c.strings(
                    "select column_name || ':' || data_type || ':' || is_nullable || ':' || " +
                        "coalesce(column_default, 'none') from information_schema.columns " +
                        "where table_schema = 'submission' and table_name = 'submission' " +
                        "and column_name in ('submission_type', 'submitted_at', 'acknowledged_at') order by 1",
                )
            rows shouldBe
                listOf(
                    "acknowledged_at:timestamp with time zone:YES:none",
                    "submission_type:text:YES:none",
                    "submitted_at:timestamp with time zone:YES:none",
                )
        }
    }

    @Test
    fun `INV-AC-19464 submission_type accepts null and the four names and refuses anything else`() {
        val doc = UUID.randomUUID().toString()
        val tenant = UUID.randomUUID()
        SubmissionDb.superuser().use { c ->
            try {
                fun put(type: String?) {
                    c.exec("delete from submission.submission where doc_uuid = '$doc'")
                    c.exec(
                        "insert into submission.submission (tenant_id, doc_uuid, version, state, entity_ref, " +
                            "submission_type) values ('$tenant', '$doc', 1, 'SENT', 'E-001', " +
                            "${type?.let { "'$it'" }})",
                    )
                }
                listOf(null, "TYPE_1A", "TYPE_1B", "TYPE_2", "TYPE_3").forEach { sqlState { put(it) } shouldBe null }
                listOf("TYPE_4", "1A", "type_1a", "").forEach { sqlState { put(it) } shouldBe "23514" }
            } finally {
                c.exec("delete from submission.submission where doc_uuid = '$doc'")
            }
        }
    }

    @Test
    fun `INV-AC-19464 the backfill sets submitted_at from the highest attempt in both tenants`() {
        withSeed { c, s ->
            runBackfill()
            val at = { doc: String ->
                c.strings(
                    "select coalesce(submitted_at::text, 'null') from submission.submission where doc_uuid = '$doc'",
                )
            }
            val expected = c.strings("select '$SECOND'::timestamptz::text")
            at(s.docs[0]) shouldBe expected
            at(s.docs[3]) shouldBe expected
            listOf(1, 2, 4, 5).forEach { at(s.docs[it]) shouldBe listOf("null") }
            val list = s.docs.joinToString(",") { "'$it'" }
            c.strings(
                "select count(*)::text from submission.submission where doc_uuid in ($list) " +
                    "and (acknowledged_at is not null or submission_type is not null)",
            ) shouldBe listOf("0")
        }
    }

    @Test
    fun `INV-AC-19464 a second backfill run changes no value`() {
        withSeed { c, s ->
            runBackfill()
            val list = s.docs.joinToString(",") { "'$it'" }
            val q =
                "select doc_uuid || '=' || coalesce(submitted_at::text, 'null') from submission.submission " +
                    "where doc_uuid in ($list) order by 1"
            val first = c.strings(q)
            // a later value would show if the guard `submitted_at is null` were missing
            c.exec(
                "update submission.submission_attempt set responded_at = '2026-12-01T00:00:00Z' " +
                    "where attempt_number = 2 and doc_uuid in ($list)",
            )
            runBackfill()
            c.strings(q) shouldBe first
        }
    }

    @Test
    fun `INV-AC-19464 row level security is forced on both tables after the backfill`() {
        withSeed { c, _ ->
            runBackfill()
            c.strings(
                "select relname || ':' || relforcerowsecurity from pg_class " +
                    "where relnamespace = 'submission'::regnamespace " +
                    "and relname in ('submission', 'submission_attempt') order by 1",
            ) shouldBe listOf("submission:true", "submission_attempt:true")
        }
    }

    private companion object {
        const val FIRST = "2026-11-01T09:00:00Z"
        const val SECOND = "2026-11-01T09:05:00Z"
    }
}
