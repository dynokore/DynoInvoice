package com.dynokore.gst.submission.adapters

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.sql.Statement

class WorkQueueGrantsTest {
    /** Runs a scratch migration in a transaction on the superuser connection, checks the catalogue, rolls back. */
    private fun findingsAfter(scratch: (Statement) -> Unit): List<String> =
        SubmissionDb.superuser().use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use(scratch)
                WorkQueueCatalogue.findings(connection)
            } finally {
                connection.rollback()
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

    private fun sqlState(block: () -> Unit): String? =
        runCatching(block).exceptionOrNull().let {
            (it as? SQLException)?.sqlState
        }

    @Test
    fun `INV-AC-2180 a role granted nothing gets insufficient_privilege on select, insert and update`() {
        SubmissionDb.superuser().use { admin ->
            admin.createStatement().use { it.execute("create role probe_reader login password 'probe_reader_pw'") }
        }
        try {
            SubmissionDb.connect("probe_reader", "probe_reader_pw").use { reader ->
                val denied =
                    listOf(
                        sqlState { reader.createStatement().use { it.execute("select * from submission.work_queue") } },
                        sqlState {
                            reader.createStatement().use {
                                it.execute(
                                    "insert into submission.work_queue (tenant_id, partition_key, lane, payload) " +
                                        "values (gen_random_uuid(), 'k', 'LIVE', '{}')",
                                )
                            }
                        },
                        sqlState {
                            reader.createStatement().use {
                                it.execute(
                                    "update submission.work_queue set attempts = 1",
                                )
                            }
                        },
                    )
                denied shouldBe listOf("42501", "42501", "42501")
            }
        } finally {
            SubmissionDb.superuser().use { it.createStatement().use { s -> s.execute("drop role probe_reader") } }
        }
    }

    @Test
    fun `a grant on work_queue to a scratch role makes the catalogue check fail`() {
        findingsAfter {
            it.execute("create role probe_reader; grant select on submission.work_queue to probe_reader")
        }.shouldNotBeEmpty()
    }

    @Test
    fun `a grant to a look-alike of the runtime role makes the equality fail`() {
        findingsAfter {
            it.execute(
                "create role submission_runtime_x; grant select on submission.work_queue to submission_runtime_x",
            )
        }.shouldNotBeEmpty()
    }

    @Test
    fun `a column grant to a scratch role makes the catalogue check fail`() {
        findingsAfter {
            it.execute("create role probe_reader; grant select (seq) on submission.work_queue to probe_reader")
        }.shouldNotBeEmpty()
    }

    @Test
    fun `a grant to PUBLIC makes the catalogue check fail`() {
        findingsAfter { it.execute("grant select on submission.work_queue to public") }.shouldNotBeEmpty()
    }

    @Test
    fun `a work_queue in a separate schema makes the catalogue check fail`() {
        findingsAfter {
            it.execute("create schema submission_queue; create table submission_queue.work_queue (seq bigint)")
        }.shouldNotBeEmpty()
    }

    @Test
    fun `a work_queue only in a look-alike schema is not accepted as submission`() {
        findingsAfter {
            it.execute(
                "alter table submission.work_queue set schema public; create schema submission_x; " +
                    "alter table public.work_queue set schema submission_x",
            )
        }.shouldNotBeEmpty()
    }

    @Test
    fun `a table named queue_item in the submission schema makes the catalogue check fail`() {
        findingsAfter { it.execute("create table submission.queue_item (id bigint)") }.shouldNotBeEmpty()
    }
}
