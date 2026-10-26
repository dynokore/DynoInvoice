package com.dynokore.platform.serviceruntime.replica

import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/** INV-AC-9052, INV-Q-9049 A: the entity set and the stateless tombstone touch one (tenant, principal) only. */
class AuthorityReplicaPartitionTest {
    private val clock = MutableClock()
    private val replica = AuthorityReplica(clock)
    private val feed = FakeFeed(replica)
    private val valid = Grant("approve", ScopeLevel.ENTITY, "x", clock.instant().minusSeconds(60))
    private val expired = valid.copy(validUntil = clock.instant().minusSeconds(1))

    private fun tenant(n: Int) = TenantId(UUID(0, n.toLong() + 1000))

    private fun load(
        records: Int,
        tenants: Int,
    ) {
        val principals = (0 until 100).map { "bg$it" }
        val entities = (0 until 10).map { "e$it" }
        val perTenant = records / tenants
        var version = 1L
        for (t in 0 until tenants) {
            val tenant = tenant(t)
            for (i in 0 until perTenant) {
                val key =
                    AuthorityKey(
                        tenant,
                        principals[i / entities.size % principals.size],
                        entities[
                            i %
                                entities.size,
                        ],
                    )
                replica.grants.apply(
                    ReplicaChange.Upsert(key, GrantState(listOf(valid), version), version++),
                )
            }
        }
    }

    private fun target(
        tenant: TenantId,
        principal: String,
        count: Int,
        expiredCount: Int,
    ) {
        for (i in 0 until count) {
            val grants = listOf(if (i < expiredCount) expired else valid)
            val key = AuthorityKey(tenant, principal, "t$i")
            replica.grants.apply(ReplicaChange.Upsert(key, GrantState(grants, 5_000_000L + i), 5_000_000L + i))
        }
    }

    @Test
    fun `entity set and stateless tombstone visit exactly the principal's 50 records`() {
        load(1_000_000, 1_000)
        val tenant = tenant(7)
        target(tenant, "alice", 50, 5)
        target(tenant, "alice2", 3, 0)
        target(tenant(8), "alice", 4, 0)
        val before = replica.grants.heldWhere { true }

        var visits = replica.grants.visitCount
        replica.heldEntities(tenant, "alice") shouldBe (5 until 50).map { "t$it" }.toSet()
        replica.grants.visitCount - visits shouldBe 50

        visits = replica.grants.visitCount
        feed.publish(AuthorityChanged(tenant, "alice", 6_000_000L, null))
        replica.grants.visitCount - visits shouldBe 50

        val after = replica.grants.heldWhere { true }
        after.size shouldBe before.size - 50
        after shouldBe before.filterKeys { !(it.tenant == tenant && it.principalId == "alice") }
        replica.heldEntities(tenant, "alice") shouldBe emptySet()
        replica.heldEntities(tenant, "alice2").size shouldBe 3
        replica.heldEntities(tenant(8), "alice").size shouldBe 4
        // versions are kept: an older redelivery stays out
        replica.grants.apply(
            ReplicaChange.Upsert(
                AuthorityKey(tenant, "alice", "t10"),
                GrantState(listOf(valid), 5_000_010L),
                5_000_010L,
            ),
        )
        replica.heldEntities(tenant, "alice") shouldBe emptySet()
    }

    @Test
    fun `heldEntities at 1,000,000 records is within 5 times its time at 1,000 records`() {
        fun median(): Long {
            val tenant = tenant(3)
            target(tenant, "alice", 50, 5)
            repeat(50) { replica.heldEntities(tenant, "alice") }
            val times =
                (1..200)
                    .map {
                        val start = System.nanoTime()
                        replica.heldEntities(tenant, "alice")
                        System.nanoTime() - start
                    }.sorted()
            return times[times.size / 2]
        }
        load(1_000, 10)
        val small = median()
        load(1_000_000, 1_000)
        val large = median()
        val ratio = large.toDouble() / small.toDouble().coerceAtLeast(1.0)
        println("heldEntities median: 1,000 records ${small}ns, 1,000,000 records ${large}ns, ratio $ratio")
        (ratio <= 5.0) shouldBe true
    }

    @Test
    fun `heldWhere and tombstoneIf scan every entry of a partitioned replica and count them`() {
        val flat = EventFedReplica<String, String>("t", clock)
        val parted = EventFedReplica<String, String>("t", clock, partitionOf = { it.substringBefore(':') })
        listOf(flat, parted).forEach { r ->
            listOf("a:1", "a:2", "b:1").forEachIndexed { i, k -> r.apply(ReplicaChange.Upsert(k, "v$i", i + 1L)) }
            r.heldWhere { it.startsWith("a") } shouldBe mapOf("a:1" to "v0", "a:2" to "v1")
            r.visitCount shouldBe 3
            r.tombstoneIf { it == "b:1" }
            r.size() shouldBe 2
            r.apply(ReplicaChange.Upsert("b:1", "old", 3L))
            r.peek("b:1") shouldBe null
        }
        parted.heldIn("a") shouldBe mapOf("a:1" to "v0", "a:2" to "v1")
        parted.heldIn("zz") shouldBe emptyMap()
        val v = parted.visitCount
        parted.tombstoneIn("a")
        parted.visitCount - v shouldBe 2
        parted.size() shouldBe 0
    }
}
