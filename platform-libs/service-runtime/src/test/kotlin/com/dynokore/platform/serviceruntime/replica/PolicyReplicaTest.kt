package com.dynokore.platform.serviceruntime.replica

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** INV-REQ-1241: PolicyChanged into the replica, read locally. */
class PolicyReplicaTest {
    private val replica = PolicyReplica(MutableClock())
    private val feed = FakeFeed(replica)
    private val d1 = LocalDate.parse("2026-10-01")
    private val d2 = LocalDate.parse("2026-11-01")

    private fun changed(
        scope: PolicyScope,
        value: String,
        on: LocalDate,
        v: Long,
        source: PolicySource = PolicySource.OVERRIDE,
        ack: String? = null,
        vp: String = "VP-1",
    ) = PolicyChanged(scope, vp, value, on, source, ack, v)

    @Test
    fun `serves the value in force at a date, with its source and relaxation reference`() {
        feed.publish(changed(PolicyScope.PLATFORM, "a", d1, 1, PolicySource.DEFAULT))
        feed.publish(changed(PolicyScope.PLATFORM, "b", d2, 2, ack = "ACK-9"))
        replica.valueAt(PolicyScope.PLATFORM, "VP-1", d1.minusDays(1)) shouldBe null
        replica.valueAt(PolicyScope.PLATFORM, "VP-1", d1)?.value shouldBe "a"
        replica.valueAt(PolicyScope.PLATFORM, "VP-1", d2.minusDays(1))?.value shouldBe "a"
        replica.valueAt(PolicyScope.PLATFORM, "VP-1", d2) shouldBe PolicyValue("b", d2, PolicySource.OVERRIDE, "ACK-9")
    }

    @Test
    fun `the most specific scope with a value wins`() {
        val tenant = PolicyScope(TENANT_A)
        val entity = PolicyScope(TENANT_A, "e1")
        feed.publish(changed(PolicyScope.PLATFORM, "platform", d1, 1))
        feed.publish(changed(tenant, "tenant", d1, 2, PolicySource.PROFILE))
        feed.publish(changed(entity, "entity", d1, 3))
        replica.valueAt(entity, "VP-1", d1)?.value shouldBe "entity"
        replica.valueAt(PolicyScope(TENANT_A, "e2"), "VP-1", d1)?.value shouldBe "tenant"
        replica.valueAt(PolicyScope(TENANT_B, "e1"), "VP-1", d1)?.value shouldBe "platform"
        replica.valueAt(entity, "VP-OTHER", d1) shouldBe null
    }

    @Test
    fun `redelivery and reordering are harmless`() {
        feed.publish(changed(PolicyScope.PLATFORM, "new", d1, 5))
        feed.publish(changed(PolicyScope.PLATFORM, "old", d1, 3))
        feed.publish(changed(PolicyScope.PLATFORM, "new", d1, 5))
        replica.valueAt(PolicyScope.PLATFORM, "VP-1", d1)?.value shouldBe "new"
    }

    @Test
    fun `measures its own lag`() {
        feed.publish(changed(PolicyScope.PLATFORM, "a", d1, 1))
        replica.lag() shouldBe Lag.Measured(java.time.Duration.ZERO)
    }
}
