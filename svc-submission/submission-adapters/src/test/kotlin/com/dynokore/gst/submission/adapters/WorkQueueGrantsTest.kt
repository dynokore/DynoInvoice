package com.dynokore.gst.submission.adapters

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException

class WorkQueueGrantsTest {
    private fun inScratchMigration(
        sql: String,
        block: (Connection) -> Unit,
    ) {
        SubmissionDb.superuser().use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { it.execute(sql) }
                block(connection)
            } finally {
                connection.rollback()
            }
        }
    }

    @Test
    fun `INV-AC-2180 only the owner, the runtime login and the sys role hold a privilege on work_queue`() {
        SubmissionDb.superuser().use { connection ->
            WorkQueueCatalogue.privilegedRoles(connection) shouldBe WorkQueueCatalogue.EXPECTED_ROLES
            WorkQueueCatalogue.workQueueSchemas(connection) shouldBe listOf("submission")
            WorkQueueCatalogue.queueItemSchemas(connection).shouldBeEmpty()
            WorkQueueCatalogue.findings(connection).shouldBeEmpty()
        }
    }

    @Test
    fun `INV-AC-2180 a role granted nothing gets insufficient_privilege on select, insert and update`() {
        inScratchMigration("create role probe_reader login password 'probe_reader_pw'") { admin ->
            admin.commit()
        }
        try {
            SubmissionDb.connect("probe_reader", "probe_reader_pw").use { reader ->
                val denied =
                    listOf(
                        "select * from submission.work_queue",
                        "insert into submission.work_queue (tenant_id, partition_key, lane, payload) " +
                            "values (gen_random_uuid(), 'k', 'LIVE', '{}')",
                        "update submission.work_queue set attempts = 1",
                    ).map { statement ->
                        runCatching { reader.createStatement().use { it.execute(statement) } }
                            .exceptionOrNull().let { (it as? SQLException)?.sqlState }
                    }
                denied shouldBe listOf("42501", "42501", "42501")
            }
        } finally {
            SubmissionDb.superuser().use { it.createStatement().use { s -> s.execute("drop role probe_reader") } }
        }
    }

    @Test
    fun `a grant on work_queue to a scratch role makes the catalogue check fail`() {
        inScratchMigration(
            "create role probe_reader; grant select on submission.work_queue to probe_reader",
        ) { WorkQueueCatalogue.findings(it).shouldNotBeEmpty() }
    }

    @Test
    fun `a grant to a look-alike of the runtime role makes the equality fail`() {
        inScratchMigration(
            "create role submission_runtime_x; grant select on submission.work_queue to submission_runtime_x",
        ) { WorkQueueCatalogue.findings(it).shouldNotBeEmpty() }
    }

    @Test
    fun `a column grant to a scratch role makes the catalogue check fail`() {
        inScratchMigration(
            "create role probe_reader; grant select (seq) on submission.work_queue to probe_reader",
        ) { WorkQueueCatalogue.findings(it).shouldNotBeEmpty() }
    }

    @Test
    fun `a grant to PUBLIC makes the catalogue check fail`() {
        inScratchMigration("grant select on submission.work_queue to public") { WorkQueueCatalogue.findings(it).shouldNotBeEmpty() }
    }

    @Test
    fun `a work_queue in a separate schema makes the catalogue check fail`() {
        inScratchMigration(
            "create schema submission_queue; create table submission_queue.work_queue (seq bigint)",
        ) { WorkQueueCatalogue.findings(it).shouldNotBeEmpty() }
    }

    @Test
    fun `a work_queue only in a look-alike schema is not accepted as submission`() {
        inScratchMigration(
            "alter table submission.work_queue set schema public; create schema submission_x; " +
                "alter table public.work_queue set schema submission_x",
        ) { WorkQueueCatalogue.findings(it).shouldNotBeEmpty() }
    }

    @Test
    fun `a table named queue_item in the submission schema makes the catalogue check fail`() {
        inScratchMigration(
            "create table submission.queue_item (id bigint)",
        ) { WorkQueueCatalogue.findings(it).shouldNotBeEmpty() }
    }
}
