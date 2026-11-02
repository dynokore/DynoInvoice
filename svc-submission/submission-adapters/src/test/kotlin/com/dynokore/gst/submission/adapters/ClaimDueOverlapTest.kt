package com.dynokore.gst.submission.adapters

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Probes of the migration `claim_due_locks_only_the_claimed_row` (INV-T-15733; INV-Q-15728 A; INV-REQ-549). */
class ClaimDueOverlapTest {
    private val claim =
        "select claimed_partition from submission.claim_due('LIVE', null, now(), now() - interval '1 hour')"

    @BeforeEach
    fun before() = clean()

    @AfterEach
    fun after() = clean()

    private fun clean() {
        SubmissionDb.superuser().use { c -> c.createStatement().use { it.execute("delete from submission.work_queue") } }
    }

    private fun enqueue(partitions: List<String>) {
        val tenant = UUID.randomUUID()
        SubmissionDb.superuser().use { c ->
            partitions.forEach { p ->
                c.createStatement().use {
                    it.execute(
                        "insert into submission.work_queue (tenant_id, partition_key, lane, payload) " +
                            "values ('$tenant', '$p', 'LIVE', '{}')",
                    )
                }
            }
        }
    }

    private fun Connection.claimed(): List<String> =
        createStatement().use { st ->
            st.executeQuery(claim).use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
            }
        }

    private fun runtime(autoCommit: Boolean): Connection =
        SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).also { it.autoCommit = autoCommit }

    @Test
    @Timeout(60, unit = TimeUnit.SECONDS)
    fun `an uncommitted claim does not hide another partition`() {
        enqueue(listOf("A", "B"))
        val one = runtime(false)
        val two = runtime(true)
        try {
            one.claimed() shouldBe listOf("A")
            two.claimed() shouldBe listOf("B")
            one.commit()
            two.claimed() shouldBe emptyList()
        } finally {
            runCatching { one.close() }
            runCatching { two.close() }
        }
    }

    @Test
    @Timeout(60, unit = TimeUnit.SECONDS)
    fun `an uncommitted claim keeps the order inside one partition`() {
        enqueue(listOf("C", "C"))
        val one = runtime(false)
        val two = runtime(true)
        try {
            one.claimed() shouldBe listOf("C")
            two.claimed() shouldBe emptyList()
            one.commit()
            SubmissionDb.superuser().use { c ->
                c.createStatement().use { it.execute("delete from submission.work_queue where seq = (select min(seq) from submission.work_queue)") }
            }
            two.claimed() shouldBe listOf("C")
        } finally {
            runCatching { one.close() }
            runCatching { two.close() }
        }
    }
}
