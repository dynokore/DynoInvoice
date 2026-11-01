package com.dynokore.gst.submission.adapters

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

/** Probes of the migration `attempt_outcome` (INV-T-7277; INV-Q-14313 A, INV-Q-14325 A). */
class AttemptOutcomeMigrationTest {
    /** A `submission` row; [first] and [next] are timestamps as text or null. */
    private class Row(
        val tenant: UUID = UUID.randomUUID(),
        val doc: UUID = UUID.randomUUID(),
        val state: String = "RETRY",
        val failures: Int,
        val first: String?,
        val next: String? = null,
    )

    private fun insert(
        connection: Connection,
        row: Row,
    ) = connection.createStatement().use {
        it.execute(
            "insert into submission.submission (tenant_id, doc_uuid, version, state, entity_ref, retry_failures, " +
                "retry_first_failure_at, next_attempt_at) values ('${row.tenant}', '${row.doc}', 1, '${row.state}', " +
                "'E-001', ${row.failures}, ${row.first?.let { v -> "'$v'" }}, ${row.next?.let { v -> "'$v'" }})",
        )
    }

    private fun delete(
        connection: Connection,
        doc: UUID,
    ) = connection.createStatement().use { it.execute("delete from submission.submission where doc_uuid = '$doc'") }

    private fun sqlState(block: () -> Unit): String? = (runCatching(block).exceptionOrNull() as? SQLException)?.sqlState

    private fun <T> withRows(
        rows: List<Row>,
        block: (Connection) -> T,
    ): T {
        SubmissionDb.superuser().use { c -> rows.forEach { insert(c, it) } }
        try {
            return SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).use(block)
        } finally {
            SubmissionDb.superuser().use { c -> rows.forEach { delete(c, it.doc) } }
        }
    }

    private fun Connection.strings(sql: String): List<String> =
        createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
            }
        }

    @Test
    fun `a failure count of 0 with a first failure instant is refused by the CHECK`() {
        SubmissionDb.superuser().use { c ->
            sqlState { insert(c, Row(failures = 0, first = FIRST)) } shouldBe "23514"
        }
    }

    @Test
    fun `a failure count of 1 without a first failure instant is refused by the CHECK`() {
        SubmissionDb.superuser().use { c ->
            sqlState { insert(c, Row(failures = 1, first = null)) } shouldBe "23514"
        }
    }

    @Test
    fun `as the runtime role a direct select of another tenant's submission row returns no row`() {
        val row = Row(failures = 1, first = FIRST, next = DUE)
        withRows(listOf(row)) { runtime ->
            runtime.strings("select doc_uuid from submission.submission where doc_uuid = '${row.doc}'") shouldBe
                emptyList()
        }
    }

    @Test
    fun `retry_due_tenants returns one uuid column holding only the tenant of a due RETRY row`() {
        val due = Row(failures = 1, first = FIRST, next = DUE)
        val later = Row(failures = 1, first = FIRST, next = "2026-11-01T10:00:01Z")
        val sent = Row(state = "SENT", failures = 0, first = null, next = FIRST)
        withRows(listOf(due, later, sent)) { runtime ->
            runtime.createStatement().use { st ->
                st.executeQuery("select * from submission.retry_due_tenants('$DUE')").use { rs ->
                    rs.metaData.columnCount shouldBe 1
                    rs.metaData.getColumnTypeName(1) shouldBe "uuid"
                }
            }
            val seen = runtime.strings("select * from submission.retry_due_tenants('$DUE')")
            (due.tenant.toString() in seen) shouldBe true
            (later.tenant.toString() in seen) shouldBe false
            (sent.tenant.toString() in seen) shouldBe false
        }
    }

    private companion object {
        const val FIRST = "2026-11-01T09:00:00Z"
        const val DUE = "2026-11-01T10:00:00Z"
    }
}
