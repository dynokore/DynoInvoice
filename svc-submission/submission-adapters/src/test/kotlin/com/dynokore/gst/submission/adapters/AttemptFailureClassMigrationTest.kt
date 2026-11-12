package com.dynokore.gst.submission.adapters

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

/** Probes of the migration `attempt_failure_class` (INV-T-20587, INV-REQ-575, INV-AC-20586). */
class AttemptFailureClassMigrationTest {
    private fun <T> inRolledBackTransaction(block: (Connection) -> T): T =
        SubmissionDb.superuser().use { c ->
            c.autoCommit = false
            try {
                block(c)
            } finally {
                c.rollback()
            }
        }

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.seed(doc: UUID) {
        exec(
            "insert into submission.submission (tenant_id, doc_uuid, version, state, entity_ref) " +
                "values (gen_random_uuid(), '$doc', 1, 'DEAD_LETTERED', 'E-001')",
        )
        exec(tenantInsert(doc))
    }

    private fun tenantInsert(doc: UUID) =
        "insert into submission.submission_attempt (tenant_id, doc_uuid, version, attempt_number, correlation_id, " +
            "requested_at) select tenant_id, doc_uuid, version, 1, gen_random_uuid(), now() " +
            "from submission.submission where doc_uuid = '$doc'"

    private fun Connection.strings(sql: String): List<String?> =
        createStatement().use { st ->
            st.executeQuery(sql).use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
        }

    private fun sqlState(block: () -> Unit): String? = (runCatching(block).exceptionOrNull() as? SQLException)?.sqlState

    @Test
    fun `INV-AC-20586 M1 the column exists, is nullable, has no default and an existing row keeps null`() {
        inRolledBackTransaction { c ->
            c.strings(
                "select is_nullable || '/' || coalesce(column_default, 'none') || '/' || data_type " +
                    "from information_schema.columns where table_schema = 'submission' " +
                    "and table_name = 'submission_attempt' and column_name = 'failure_class'",
            ) shouldBe listOf("YES/none/text")
            val doc = UUID.randomUUID()
            c.seed(doc)
            c.strings(
                "select count(*)::text from submission.submission_attempt " +
                    "where doc_uuid = '$doc' and failure_class is null",
            ) shouldBe listOf("1")
        }
    }

    @Test
    fun `INV-AC-20586 M2 a malformed value violates the check and a catalogue code is stored`() {
        listOf("sub-001", "SUB-0010", " SUB-001", "SUB-001\n", "").forEach { bad ->
            inRolledBackTransaction { c ->
                val doc = UUID.randomUUID()
                c.seed(doc)
                sqlState {
                    c.exec("update submission.submission_attempt set failure_class = '$bad' where doc_uuid = '$doc'")
                } shouldBe "23514"
            }
        }
        inRolledBackTransaction { c ->
            val doc = UUID.randomUUID()
            c.seed(doc)
            c.exec("update submission.submission_attempt set failure_class = 'SUB-001' where doc_uuid = '$doc'")
            c.strings("select failure_class from submission.submission_attempt where doc_uuid = '$doc'") shouldBe
                listOf("SUB-001")
        }
    }
}
