package com.dynokore.gst.reconciliation.adapters

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * A claim locks only the row it takes (INV-REQ-549, INV-AC-14143; PostgreSQL explicit locking, the
 * `FOR UPDATE SKIP LOCKED` queue pattern). An uncommitted claim must not hide the rows of other partitions.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ClaimDueOverlapTest {
    private val tenant = UUID.fromString("00000000-0000-0000-0000-00000000a001")

    @BeforeEach
    fun clean() = deleteAll()

    @AfterEach
    fun cleanAfter() = deleteAll()

    @Test
    fun `an uncommitted claim does not hide another partition`() {
        enqueue("A")
        enqueue("B")
        val first = ReconciliationDb.connect(ReconciliationDb.RUNTIME, ReconciliationDb.RUNTIME_PASSWORD)
        val second = ReconciliationDb.connect(ReconciliationDb.RUNTIME, ReconciliationDb.RUNTIME_PASSWORD)
        try {
            first.autoCommit = false
            second.autoCommit = true
            claim(first) shouldBe listOf("A")
            claim(second) shouldBe listOf("B")
            first.commit()
            claim(second) shouldBe emptyList()
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun `an uncommitted claim keeps the order inside one partition`() {
        val c1 = enqueue("C")
        enqueue("C")
        val first = ReconciliationDb.connect(ReconciliationDb.RUNTIME, ReconciliationDb.RUNTIME_PASSWORD)
        val second = ReconciliationDb.connect(ReconciliationDb.RUNTIME, ReconciliationDb.RUNTIME_PASSWORD)
        try {
            first.autoCommit = false
            second.autoCommit = true
            claimSeqs(first) shouldBe listOf(c1)
            claimSeqs(second) shouldBe emptyList()
            first.commit()
            ReconciliationDb.superuser().use { su ->
                su.prepareStatement("delete from reconciliation.work_queue where seq = ?").use {
                    it.setLong(1, c1)
                    it.executeUpdate()
                }
            }
            claimSeqs(second).size shouldBe 1
        } finally {
            first.close()
            second.close()
        }
    }

    private fun claim(connection: Connection): List<String> =
        connection
            .prepareStatement(
                "select claimed_partition from reconciliation.claim_due('LIVE', null, now(), now() - interval '1 hour')",
            ).use { st -> st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } }

    private fun claimSeqs(connection: Connection): List<Long> =
        connection
            .prepareStatement(
                "select claimed_seq from reconciliation.claim_due('LIVE', null, now(), now() - interval '1 hour')",
            ).use { st -> st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getLong(1)) } } }

    private fun enqueue(partition: String): Long =
        ReconciliationDb.superuser().use { su ->
            su
                .prepareStatement(
                    "insert into reconciliation.work_queue (tenant_id, partition_key, lane, payload) " +
                        "values (?, ?, 'LIVE', '{}'::jsonb) returning seq",
                ).use {
                    it.setObject(1, tenant)
                    it.setString(2, partition)
                    it.executeQuery().use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }
        }

    private fun deleteAll() {
        ReconciliationDb.superuser().use { su ->
            su.createStatement().use { it.executeUpdate("delete from reconciliation.work_queue") }
        }
    }
}
