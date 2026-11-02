package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.Connection
import java.util.concurrent.TimeUnit

/**
 * An uncommitted `claim_due` locks only the row it claims (INV-REQ-549: the queue imposes no order between
 * partitions), while the order inside one partition still holds.
 */
@Tag("integration")
class QueueClaimOverlapIT {
    private val tenant = ServiceKit.TENANT

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    fun `an uncommitted claim does not hide another partition`(image: String) {
        OutboxHarness(image).use { harness ->
            val q = QueueKit(harness)
            val a = q.enqueue(tenant, "doc-a", Lane.LIVE)
            val b = q.enqueue(tenant, "doc-b", Lane.LIVE)
            assertTrue(a < b)
            val ds = harness.fixture.runtimeDataSource()
            val c1 = ds.connection
            val c2 = ds.connection
            try {
                c1.autoCommit = false
                c2.autoCommit = false
                claim(c1) shouldBe listOf("doc-a")
                claim(c2) shouldBe listOf("doc-b")
                c1.commit()
                c2.commit()
                claim(c1) shouldBe emptyList()
                c1.commit()
            } finally {
                c1.close()
                c2.close()
            }
            cleanUp(q, a to "doc-a", b to "doc-b")
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    fun `an uncommitted claim keeps the order inside one partition`(image: String) {
        OutboxHarness(image).use { harness ->
            val q = QueueKit(harness)
            val c1Seq = q.enqueue(tenant, "doc-c", Lane.LIVE)
            val c2Seq = q.enqueue(tenant, "doc-c", Lane.LIVE)
            val ds = harness.fixture.runtimeDataSource()
            val c1 = ds.connection
            val c2 = ds.connection
            try {
                c1.autoCommit = false
                c2.autoCommit = false
                val first = claimRows(c1)
                first.map { it.first } shouldBe listOf(c1Seq)
                claim(c2) shouldBe emptyList()
                c1.commit()
                c2.commit()
                q.kit.inTx(tenant) { q.queue.complete(ClaimedItem(c1Seq, TenantId(tenant), "doc-c", Lane.LIVE)) }
                claimRows(c2).map { it.first } shouldBe listOf(c2Seq)
                c2.commit()
            } finally {
                c1.close()
                c2.close()
            }
            cleanUp(q, c2Seq to "doc-c")
        }
    }

    private fun claim(c: Connection): List<String> = claimRows(c).map { it.second }

    /** The (seq, partition) rows `claim_due` returns on [c], inside the open transaction of [c]. */
    private fun claimRows(c: Connection): List<Pair<Long, String>> =
        c.createStatement().use { st ->
            st
                .executeQuery(
                    "select claimed_seq, claimed_partition from submission.claim_due('LIVE', null, now(), " +
                        "now() - interval '1 hour')",
                ).use { rs ->
                    buildList { while (rs.next()) add(rs.getLong(1) to rs.getString(2)) }
                }
        }

    /** Completes the claimed items the test left, so no row stays behind. */
    private fun cleanUp(
        q: QueueKit,
        vararg left: Pair<Long, String>,
    ) {
        left.forEach { (seq, partition) ->
            q.kit.inTx(tenant) { q.queue.complete(ClaimedItem(seq, TenantId(tenant), partition, Lane.LIVE)) }
        }
        q.harness.countIn(tenant, "select count(*) from submission.work_queue") shouldBe 0L
    }
}
