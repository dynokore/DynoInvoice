package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

/** The read of the partition keys of the unclaimed items of the bound tenant (INV-REQ-554, INV-AC-21942). */
@Tag("integration")
class PendingPartitionReaderIT {
    private val tenant = ServiceKit.TENANT

    private fun QueueKit.pending(who: UUID = tenant): List<String> = kit.inTx(who) { queue.pendingPartitionKeys() }

    /** P3 and the first P4 item (DEADLINE_NEAR, served first) are claimed; P1 has two lanes; P5 is another tenant. */
    private fun scenario(q: QueueKit): List<ClaimedItem> {
        q.enqueue(tenant, "P3", Lane.DEADLINE_NEAR)
        q.enqueue(tenant, "P4", Lane.DEADLINE_NEAR)
        q.enqueue(tenant, "P4", Lane.LIVE)
        q.enqueue(tenant, "P1", Lane.LIVE)
        q.enqueue(tenant, "P1", Lane.BACKFILL)
        q.enqueue(tenant, "P2", Lane.LIVE)
        q.enqueue(UUID.randomUUID(), "P5", Lane.LIVE)
        val claimed = listOf(notNull(q.queue.claimNext()), notNull(q.queue.claimNext()))
        assertEquals(listOf("P3", "P4"), claimed.map { it.partitionKey })
        return claimed
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-AC-21942 lists exactly P1 P2 P4 once each and never the claimed P3 or another tenant's P5`(image: String) {
        OutboxHarness(image).use { harness ->
            val q = QueueKit(harness)
            scenario(q)
            assertEquals(listOf("P1", "P2", "P4"), q.pending())
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-AC-21942 complete leaves P3 unlisted and release lists the partition of a claimed item`(image: String) {
        OutboxHarness(image).use { harness ->
            val q = QueueKit(harness)
            val (p3, _) = scenario(q)
            q.kit.inTx(tenant) { q.queue.complete(p3) }
            assertEquals(listOf("P1", "P2", "P4"), q.pending())
            q.enqueue(tenant, "P3", Lane.DEADLINE_NEAR)
            assertEquals(listOf("P1", "P2", "P3", "P4"), q.pending())
            val again = notNull(q.queue.claimNext())
            assertEquals("P3", again.partitionKey)
            assertEquals(listOf("P1", "P2", "P4"), q.pending())
            q.kit.inTx(tenant) { q.queue.release(again) }
            assertEquals(listOf("P1", "P2", "P3", "P4"), q.pending())
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-AC-21942 a tenant with no items gets an empty list`(image: String) {
        OutboxHarness(image).use { harness ->
            val q = QueueKit(harness)
            q.enqueue(tenant, "P1", Lane.LIVE)
            assertEquals(emptyList<String>(), q.pending(UUID.randomUUID()))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-AC-21942 the keys are distinct and ascending whatever the enqueue order, with the plan forced to hash`(
        image: String,
    ) {
        OutboxHarness(image).use { harness ->
            val q = QueueKit(harness)
            val keys = List(300) { UUID.randomUUID().toString() }
            (keys.shuffled() + keys.shuffled()).forEach { q.enqueue(tenant, it, Lane.LIVE) }
            val (plan, read) =
                q.kit.inTx(tenant) {
                    // plain JDBC on the transaction's own connection: the guarded DSL refuses statements other than DML
                    val plan =
                        q.kit.dsl.connectionResult { c ->
                            c.createStatement().use { st ->
                                FORCE_HASH.forEach { st.execute(it) }
                                st.executeQuery(EXPLAIN).use { rs ->
                                    generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
                                }
                            }
                        }
                    plan to q.queue.pendingPartitionKeys()
                }
            println("EXPLAIN without ORDER BY under the forced settings:\n" + plan.joinToString("\n"))
            val hashed =
                plan.any { it.contains("HashAggregate") } && plan.none { it.contains("Sort") || it.contains("Unique") }
            assertEquals(true, hashed, "$plan")
            assertEquals(keys.sorted(), read)
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-AC-21942 the keys are in byte order whatever the database collation`(image: String) {
        OutboxHarness(image).use { harness ->
            val q = QueueKit(harness)
            listOf("a", "B", "a-c", "ab", "Z", "_x").forEach { q.enqueue(tenant, it, Lane.LIVE) }
            assertEquals(listOf("B", "Z", "_x", "a", "a-c", "ab"), q.pending())
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-AC-21942 the tenant condition of the statement alone keeps the other tenant out`(image: String) {
        OutboxHarness(image).use { harness ->
            val q = QueueKit(harness)
            val unguarded = JdbcWorkQueue(q.scope, adminDsl(harness), "submission", q.limiter, q.clock)
            q.enqueue(tenant, "P1", Lane.LIVE)
            q.enqueue(UUID.randomUUID(), "P5", Lane.LIVE)
            val read = q.kit.tenants.bind(TenantId(tenant), "test-actor") { unguarded.pendingPartitionKeys() }
            assertEquals(listOf("P1"), read)
        }
    }

    private fun adminDsl(harness: OutboxHarness) = DSL.using(harness.fixture.adminDataSource(), SQLDialect.POSTGRES)

    private companion object {
        const val EXPLAIN = "explain select distinct partition_key from submission.work_queue where state = 'PENDING'"
        val FORCE_HASH =
            listOf(
                "set local enable_sort = off",
                "set local enable_indexscan = off",
                "set local enable_indexonlyscan = off",
                "set local enable_bitmapscan = off",
            )
    }
}
