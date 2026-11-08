package com.dynokore.platform.serviceruntime.replica

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration

/** INV-T-9262, INV-Q-9243 B (a): the authority replica orders a change or head by (incarnation, version). */
class AuthorityIncarnationTest {
    private val clock = MutableClock()
    private val counts = mutableMapOf<String, Int>()
    private val replica =
        AuthorityReplica(
            clock,
            TEST_ROLES,
            orderCounter = { counts.merge(it, 1, Int::plus) },
        )
    private val feed = FakeFeed(replica)
    private val grant = RoleGrant("approver", GrantScope.ENTITY, "e1", clock.instant().minusSeconds(60))
    private val human = Principal("p1", PrincipalKind.HUMAN)
    private var cleared = 0

    init {
        replica.onEpochCleared { cleared++ }
    }

    private fun change(
        version: Long,
        incarnation: Long?,
        ref: String = "g1",
    ) = feed.publish(
        AuthorityChanged(TENANT_A, "p1", version, roleGrantRecord(ref, grant), incarnation),
    )

    private fun held() = replica.roleGrants.heldWhere { true }.size

    private fun decision() =
        replica.decide(AuthorizationRequest(TENANT_A, human, "e1", "approve"), deadline(clock)).source

    @Test
    fun `INV-AC-9051 a lower incarnation with a higher version is refused, counted and nothing stored`() {
        change(5, 2)
        change(900, 1, "g2")
        held() shouldBe 1
        replica.tracker.appliedVersion() shouldBe 5
        counts[ReplicaOrderCounter.LOWER_INCARNATION] shouldBe 1
        cleared shouldBe 0
    }

    @Test
    fun `INV-AC-9051 a higher incarnation clears the store, makes the lag unknown and runs the cold start hook`() {
        change(5, 1)
        decision() shouldBe DecisionSource.LOCAL
        change(2, 2, "g2")
        cleared shouldBe 1
        replica.lag() shouldBe Lag.Unknown
        decision() shouldBe DecisionSource.DEGRADED // no fallback in this fixture: not decided locally
        replica.roleGrants
            .heldWhere { true }
            .keys
            .map { it.grantRef } shouldBe listOf("g2")
        feed.snapshotComplete(2)
        decision() shouldBe DecisionSource.LOCAL
    }

    @Test
    fun `INV-AC-9051 an equal incarnation clears nothing and the old version rule keeps the held state`() {
        change(5, 2)
        change(7, 2, "g2")
        change(3, 2, "g1") // same epoch, lower version for g1's key: held (version 5) stays
        cleared shouldBe 0
        held() shouldBe 2
        replica.roleGrants.store
            .get(RoleGrantKey(TENANT_A, "p1", "g1"))
            ?.version shouldBe 5
        counts[ReplicaOrderCounter.LOWER_INCARNATION] shouldBe null
    }

    @Test
    fun `INV-AC-9051 epoch 1 and an absent incarnation are the same epoch`() {
        change(5, 1)
        change(6, null, "g2")
        change(7, 1, "g3")
        cleared shouldBe 0
        held() shouldBe 3
        replica.tracker.incarnation() shouldBe 1
    }

    @Test
    fun `INV-AC-9051 an absent incarnation is below epoch 2`() {
        change(5, 2)
        change(6, null, "g2")
        counts[ReplicaOrderCounter.LOWER_INCARNATION] shouldBe 1
        held() shouldBe 1
    }

    @Test
    fun `INV-AC-9051 a head of a higher incarnation clears, a head of a lower one is ignored and counted`() {
        change(5, 2)
        replica.observeHead(9, 1)
        counts[ReplicaOrderCounter.LOWER_INCARNATION] shouldBe 1
        held() shouldBe 1
        replica.observeHead(1, 3)
        cleared shouldBe 1
        held() shouldBe 0
        replica.lag() shouldBe Lag.Unknown
    }

    @Test
    fun `INV-AC-9051 a head below the head held in the same incarnation is ignored for the lag and counted`() {
        change(5, 1)
        replica.observeHead(8, 1)
        clock.advance(Duration.ofMillis(600))
        replica.observeHead(6, 1) // regressed: the behind-since clock of head 8 keeps running
        clock.advance(Duration.ofMillis(600))
        counts[ReplicaOrderCounter.HEAD_REGRESSED] shouldBe 1
        replica.lag() shouldBe Lag.Measured(Duration.ofMillis(1200))
        replica.observeHead(8, 1) // equal is not a regression
        counts[ReplicaOrderCounter.HEAD_REGRESSED] shouldBe 1
    }

    @Test
    fun `INV-AC-9051 the first incarnation seen clears nothing`() {
        change(5, 7)
        cleared shouldBe 0
        held() shouldBe 1
    }

    @Test
    fun `INV-AC-9051 lowerIncarnationHeadIsCountedAndIgnored`() {
        change(5, 2)
        replica.observeHead(5, 2)
        clock.advance(Duration.ofMillis(300))
        val lagBefore = replica.lag()
        val appliedBefore = replica.tracker.appliedVersion()
        replica.observeHead(900, 1) // epoch 1 is below the held epoch 2, with a higher version
        counts[ReplicaOrderCounter.LOWER_INCARNATION] shouldBe 1
        replica.lag() shouldBe lagBefore
        replica.tracker.appliedVersion() shouldBe appliedBefore
        cleared shouldBe 0
        clock.advance(Duration.ofMillis(300))
        replica.lag() shouldBe Lag.Measured(Duration.ZERO) // head 900 never reached the tracker: still not behind
    }
}
