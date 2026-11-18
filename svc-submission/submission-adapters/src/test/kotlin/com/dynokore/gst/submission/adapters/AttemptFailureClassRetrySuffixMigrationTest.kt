package com.dynokore.gst.submission.adapters

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

/** The check of `failure_class` after `attempt_failure_class_retry_suffix` (INV-T-24285, INV-AC-24283). */
class AttemptFailureClassRetrySuffixMigrationTest {
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
                "values (gen_random_uuid(), '$doc', 1, 'REJECTED', 'E-001')",
        )
        exec(
            "insert into submission.submission_attempt (tenant_id, doc_uuid, version, attempt_number, " +
                "correlation_id, requested_at) select tenant_id, doc_uuid, version, 1, gen_random_uuid(), now() " +
                "from submission.submission where doc_uuid = '$doc'",
        )
    }

    private fun sqlStateOf(value: String): String? =
        inRolledBackTransaction { c ->
            val doc = UUID.randomUUID()
            c.seed(doc)
            val e =
                runCatching {
                    c.exec("update submission.submission_attempt set failure_class = '$value' where doc_uuid = '$doc'")
                }.exceptionOrNull()
            (e as? SQLException)?.sqlState
        }

    @Test
    fun `INV-AC-24283 the retry suffix shape and the earlier shapes are accepted`() {
        listOf("SUB-004", "SUB-004-R1", "SUB-004-R12", "X-998", "X-999").forEach { sqlStateOf(it) shouldBe null }
    }

    @Test
    fun `INV-AC-24283 look-alikes of the suffix and the earlier look-alikes are refused`() {
        listOf(
            "SUB-004-R",
            "SUB-004-r1",
            "SUB-004-R1-R2",
            "SUB-004-R1 ",
            "SUB-004-R1\n",
            "SUB-004R1",
            "SUB-004-S1",
            "-R1",
            "X-998-R1",
            "SUB-0040",
            "sub-001",
            "",
        ).forEach { sqlStateOf(it) shouldBe "23514" }
    }
}
