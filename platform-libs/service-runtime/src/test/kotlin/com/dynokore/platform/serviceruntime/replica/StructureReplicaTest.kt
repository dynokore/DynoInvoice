package com.dynokore.platform.serviceruntime.replica

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate

/** INV-REQ-1200, with a fake feed and a fake tenancy-svc. */
class StructureReplicaTest {
    private val clock = MutableClock()
    private val tenancy =
        FakeFallback<StructureKey, StructureNode> {
            FallbackResult.Found(StructureNode(StructureNodeKind.ENTITY, "from-tenancy"))
        }
    private val replica = StructureReplica(clock, tenancy)
    private val feed = FakeFeed(replica)

    private fun org(
        ref: String,
        parent: String?,
        change: StructureChange,
        v: Long,
        tenant: com.dynokore.platform.tenancykernel.TenantId = TENANT_A,
        kind: StructureNodeKind = StructureNodeKind.ENTITY,
    ) = OrganisationStructureChanged(tenant, ref, kind, parent, change, v)

    @Test
    fun `applies both events to the replica and serves them without calling tenancy-svc`() {
        feed.publish(org("e1", "g1", StructureChange.CREATED, 1))
        feed.publish(EntityProfileChanged(TENANT_A, "e1", mapOf("uen" to "123"), LocalDate.parse("2026-10-01"), 2))
        feed.publish(EntityProfileChanged(TENANT_A, "e1", mapOf("name" to "Acme"), LocalDate.parse("2026-10-05"), 3))
        val got = replica.node(TENANT_A, "e1", deadline(clock))
        got.shouldBeInstanceOf<Lookup.Local<StructureNode>>()
        got.value.parentRef shouldBe "g1"
        got.value.attributes shouldBe mapOf("uen" to "123", "name" to "Acme")
        got.value.profileEffectiveDate shouldBe LocalDate.parse("2026-10-05")
        tenancy.calls shouldBe 0
    }

    @Test
    fun `a move and a retirement update the node`() {
        feed.publish(org("e1", "g1", StructureChange.CREATED, 1))
        feed.publish(org("e1", "g2", StructureChange.MOVED, 2))
        feed.publish(org("e1", "g2", StructureChange.RETIRED, 3))
        val node = (replica.node(TENANT_A, "e1", deadline(clock)) as Lookup.Local).value
        node.parentRef shouldBe "g2"
        node.retired shouldBe true
    }

    @Test
    fun `a miss is filled by a call to tenancy-svc, tenants are isolated`() {
        feed.publish(org("e1", "g1", StructureChange.CREATED, 1))
        replica.node(TENANT_B, "e1", deadline(clock)).shouldBeInstanceOf<Lookup.Filled<StructureNode>>()
        tenancy.calls shouldBe 1
    }

    @Test
    fun `keeps serving its last state when tenancy-svc is down`() {
        tenancy.answer = { FallbackResult.Unavailable }
        feed.publish(org("e1", "g1", StructureChange.CREATED, 1))
        feed.drop()
        clock.advance(Duration.ofHours(1))
        replica.node(TENANT_A, "e1", deadline(clock)).shouldBeInstanceOf<Lookup.Local<StructureNode>>()
        replica.node(TENANT_A, "never-seen", deadline(clock)) shouldBe Lookup.Unavailable
    }

    @Test
    fun `an out-of-date redelivery changes nothing`() {
        feed.publish(org("e1", "g2", StructureChange.MOVED, 5))
        feed.publish(org("e1", "g1", StructureChange.CREATED, 1))
        (replica.node(TENANT_A, "e1", deadline(clock)) as Lookup.Local).value.parentRef shouldBe "g2"
    }
}
