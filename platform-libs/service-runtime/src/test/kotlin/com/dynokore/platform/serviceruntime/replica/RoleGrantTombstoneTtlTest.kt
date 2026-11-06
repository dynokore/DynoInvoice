package com.dynokore.platform.serviceruntime.replica

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.Duration

/** INV-Q-8972 A, INV-Q-13768 A, INV-Q-13770 A: the tombstones of the role grants are evicted after the time-to-live. */
class RoleGrantTombstoneTtlTest {
    private val clock = MutableClock()
    private val retention = Duration.ofDays(30)
    private val ttl = retention.plusSeconds(1)
    private val from = clock.instant().minusSeconds(60)
    private val grant = RoleGrant("tenant.admin", GrantScope.TENANT, TENANT_A.toString(), from)

    private fun replica(
        ttl: Duration? = this.ttl,
        retention: Duration? = this.retention,
    ) = AuthorityReplica(clock, TEST_ROLES, tombstoneTtl = ttl, eventLogRetention = retention).also {
        it.onSnapshotComplete(0)
    }

    private fun AuthorityReplica.feed(
        ref: String,
        version: Long,
        state: RoleGrantState = RoleGrantState.ACTIVE,
        principal: String = "p1",
    ) = accept(AuthorityChanged(TENANT_A, principal, version, AuthorityRecord.RoleGrantRecord(ref, state, grant)))

    private fun AuthorityReplica.heldAt(ref: String) = roleGrants.peek(RoleGrantKey(TENANT_A, "p1", ref))

    private fun AuthorityReplica.tombstones() = roleGrants.store.tombstoneCount()

    private fun refused(
        ttl: Duration?,
        retention: Duration?,
    ): String = shouldThrow<IllegalArgumentException> { replica(ttl, retention) }.message.orEmpty()

    @Test
    fun `a ttl that is not strictly greater than the retention is refused naming both values`() {
        for (bad in listOf(retention, retention.minusSeconds(1))) {
            val message = refused(bad, retention)
            message shouldContain bad.toString()
            message shouldContain retention.toString()
        }
        replica(retention.plusSeconds(1), retention).tombstoneTtl shouldBe retention.plusSeconds(1)
    }

    @Test
    fun `exactly one of the two set is refused naming both values`() {
        for ((t, r) in listOf(ttl to null, null to retention)) {
            val message = refused(t, r)
            message shouldContain "tombstoneTtl=$t"
            message shouldContain "eventLogRetention=$r"
        }
    }

    @Test
    fun `with neither set no tombstone is evicted`() {
        val replica = AuthorityReplica(clock, TEST_ROLES)
        replica.feed("g1", 1)
        replica.feed("g1", 2, RoleGrantState.REMOVED)
        clock.advance(Duration.ofDays(100000))
        replica.feed("g2", 3)
        replica.tombstones() shouldBe 1
        replica.tombstoneTtl shouldBe null
    }

    @Test
    fun `a tombstone one second younger than the ttl survives an apply and one second older is gone`() {
        val replica = replica()
        replica.feed("g1", 1)
        replica.feed("g1", 2, RoleGrantState.REMOVED)
        clock.advance(ttl.minusSeconds(1))
        replica.feed("g2", 3)
        replica.tombstones() shouldBe 1
        clock.advance(Duration.ofSeconds(2))
        replica.feed("g2", 4)
        replica.tombstones() shouldBe 0
    }

    @Test
    fun `a held grant is never evicted`() {
        val replica = replica()
        replica.feed("g1", 1)
        clock.advance(ttl.multipliedBy(10))
        replica.feed("g2", 2)
        replica.heldAt("g1") shouldBe grant
    }

    @Test
    fun `after eviction an older upsert of the same key is accepted`() {
        // The redelivery window is the retention, which is shorter than the ttl, so no such event is expected.
        val replica = replica()
        replica.feed("g1", 5)
        replica.feed("g1", 8, RoleGrantState.REMOVED)
        replica.feed("g1", 7)
        replica.heldAt("g1") shouldBe null
        clock.advance(ttl.plusSeconds(1))
        replica.feed("g2", 9)
        replica.feed("g1", 7)
        replica.heldAt("g1") shouldBe grant
    }

    @Test
    fun `150 expired tombstones need two applies, oldest first`() {
        val replica = replica()
        for (i in 1..150) {
            replica.feed("g$i", i.toLong())
            replica.feed("g$i", 1000L + i, RoleGrantState.REMOVED)
            clock.advance(Duration.ofMillis(1))
        }
        clock.advance(ttl.plusSeconds(1))
        replica.feed("x", 5000)
        replica.tombstones() shouldBe 50
        replica.feed("x", 5001)
        replica.tombstones() shouldBe 0
    }

    @Test
    fun `the oldest tombstones go first when only some are expired`() {
        val replica = replica()
        replica.feed("old", 1)
        replica.feed("old", 2, RoleGrantState.REMOVED)
        clock.advance(Duration.ofDays(1))
        replica.feed("new", 3)
        replica.feed("new", 4, RoleGrantState.REMOVED)
        clock.advance(ttl.minus(Duration.ofHours(12)))
        replica.feed("x", 5)
        replica.tombstones() shouldBe 1
        replica.feed("old", 1)
        replica.heldAt("old") shouldBe grant
        replica.feed("new", 3)
        replica.heldAt("new") shouldBe null
    }

    @Test
    fun `a record-less event makes tombstones that are evicted too, and a re-grant stops the clock`() {
        val replica = replica()
        replica.feed("g1", 1)
        replica.feed("g2", 2)
        replica.accept(AuthorityChanged(TENANT_A, "p1", 3, null))
        replica.tombstones() shouldBe 2
        replica.feed("g1", 4)
        clock.advance(ttl.plusSeconds(1))
        replica.feed("g3", 5, principal = "p2")
        replica.tombstones() shouldBe 0
        replica.heldAt("g1") shouldBe grant
    }
}
