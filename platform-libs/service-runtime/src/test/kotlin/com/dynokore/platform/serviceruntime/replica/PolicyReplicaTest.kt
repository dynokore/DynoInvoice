package com.dynokore.platform.serviceruntime.replica

import com.dynokore.platform.configresolver.PolicyScope
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate

/** INV-REQ-1241 / INV-REQ-1242: PolicyChanged into the replica, read locally over config-resolver's scope chain. */
class PolicyReplicaTest {
    private val clock = MutableClock()
    private val replica = PolicyReplica(clock)
    private val d1 = LocalDate.parse("2026-10-01")
    private val d2 = LocalDate.parse("2026-11-01")
    private val platform = PolicyScope.installation()
    private val feed = PolicyFeed(replica)

    private fun ent(
        entity: String,
        group: String? = null,
        feed: String? = null,
        ap: String? = null,
        client: String? = null,
        tenant: com.dynokore.platform.tenancykernel.TenantId = TENANT_A,
    ) = PolicyScope.entity(tenant, entity, group, feed, ap, client)

    private fun at(scope: PolicyScope, vp: String = "VP-1") = replica.valueAt(scope, vp, d1)?.value

    private fun changed(
        scope: PolicyScope,
        value: String,
        on: LocalDate,
        offset: Long,
        source: PolicySource = PolicySource.OVERRIDE,
        ack: String? = null,
        vp: String = "VP-1",
        topic: String = "t.policy",
    ) = PolicyChanged(scope, vp, value, on, source, ack, topic, offset)

    @Test
    fun `serves the value in force at a date, with its source and relaxation reference`() {
        feed.publish(changed(platform, "a", d1, 1, PolicySource.DEFAULT))
        feed.publish(changed(platform, "b", d2, 2, ack = "ACK-9"))
        replica.valueAt(platform, "VP-1", d1.minusDays(1)) shouldBe null
        replica.valueAt(platform, "VP-1", d1)?.value shouldBe "a"
        replica.valueAt(platform, "VP-1", d2.minusDays(1))?.value shouldBe "a"
        replica.valueAt(platform, "VP-1", d2) shouldBe PolicyValue("b", d2, PolicySource.OVERRIDE, "ACK-9")
    }

    @Test
    fun `the most specific level of the chain with a value wins, over all seven levels`() {
        val full = ent("e1", "g1", "f1", "ap1", "c1")
        val levels =
            listOf(
                platform,
                PolicyScope.tenant(TENANT_A),
                PolicyScope.tenant(TENANT_A, group = "g1"),
                ent("e1"),
                ent("e1", feed = "f1"),
                ent("e1", ap = "ap1"),
                ent("e1", client = "c1"),
            )
        levels.forEachIndexed { i, scope -> feed.publish(changed(scope, "level$i", d1, i + 1L)) }
        at(full) shouldBe "level6"
        at(ent("e1", "g1", "f1", "ap1")) shouldBe "level5"
        at(ent("e1", "g1", "f1")) shouldBe "level4"
        at(ent("e1", "g1")) shouldBe "level3"
        at(ent("e2", "g1")) shouldBe "level2"
        at(ent("e2")) shouldBe "level1"
        at(ent("e1", tenant = TENANT_B)) shouldBe "level0"
        at(full, "VP-OTHER") shouldBe null
    }

    @Test
    fun `a value per AP connection applies to that connection only (INV-REQ-1241 example)`() {
        val vp = "INV-REQ-1311"
        feed.publish(changed(platform, "default", d1, 1, vp = vp))
        feed.publish(changed(ent("e1", ap = "ap1"), "strict", d1, 2, vp = vp))
        at(ent("e1", ap = "ap1"), vp) shouldBe "strict"
        at(ent("e1", ap = "ap2"), vp) shouldBe "default"
        at(ent("e1"), vp) shouldBe "default"
    }

    @Test
    fun `redelivery and reordering within a topic are decided by the event-log offset`() {
        feed.publish(changed(platform, "new", d1, 5))
        feed.publish(changed(platform, "old", d1, 3))
        feed.publish(changed(platform, "new", d1, 5))
        replica.valueAt(platform, "VP-1", d1)?.value shouldBe "new"
    }

    @Test
    fun `offsets of different topics are not compared`() {
        feed.publish(changed(platform, "a", d1, 900, topic = "t.one"))
        feed.publish(changed(platform, "b", d1, 2, topic = "t.two"))
        replica.valueAt(platform, "VP-1", d1)?.value shouldBe "b"
    }

    @Test
    fun `lag is measured per producer topic and the replica shows the worst`() {
        replica.lag() shouldBe Lag.Unknown
        feed.snapshotComplete("t.one", 0)
        feed.snapshotComplete("t.two", 0)
        feed.publish(changed(platform, "a", d1, 1, topic = "t.one"))
        replica.lag() shouldBe Lag.Measured(Duration.ZERO)
        replica.appliedOffset("t.one") shouldBe 1
        replica.appliedOffset("t.two") shouldBe 0
        feed.heartbeat("t.two", 7)
        clock.advance(Duration.ofSeconds(3))
        replica.lag("t.one") shouldBe Lag.Measured(Duration.ZERO)
        replica.lag("t.two") shouldBe Lag.Measured(Duration.ofSeconds(3))
        replica.lag() shouldBe Lag.Measured(Duration.ofSeconds(3))
    }

    @Test
    fun `lag stays unknown until every topic has synced`() {
        val listed = PolicyReplica(clock, setOf("t.one", "t.two"))
        listed.onSnapshotComplete("t.one", 0)
        listed.lag() shouldBe Lag.Unknown
        listed.onSnapshotComplete("t.two", 0)
        listed.lag() shouldBe Lag.Measured(Duration.ZERO)
        listed.onDisconnected("t.two")
        clock.advance(Duration.ofSeconds(2))
        listed.lag() shouldBe Lag.Measured(Duration.ofSeconds(2))
    }

    /** A fake feed for the policy replica: offsets are the event log's, per topic. */
    private class PolicyFeed(private val sink: PolicyFeedSink) {
        fun publish(event: PolicyChanged) = sink.accept(event)

        fun snapshotComplete(topic: String, offset: Long) = sink.onSnapshotComplete(topic, offset)

        fun heartbeat(topic: String, offset: Long) = sink.observeHead(topic, offset)
    }
}
