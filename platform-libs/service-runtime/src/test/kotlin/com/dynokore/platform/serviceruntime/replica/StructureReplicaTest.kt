package com.dynokore.platform.serviceruntime.replica

import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate

/** INV-REQ-1200, INV-Q-3324, INV-Q-3337: whole-node replacement, with a fake feed and a fake tenancy-svc. */
class StructureReplicaTest {
    private val clock = MutableClock()
    private val tenancy =
        FakeFallback<StructureKey, StructureNode> { FallbackResult.Found(node("from-tenancy", 1)) }
    private val replica = StructureReplica(clock, tenancy)
    private val feed = FakeFeed(replica)

    private fun node(
        parent: String?,
        v: Long,
        name: String = "Acme",
        retired: Boolean = false,
        profile: List<ProfileEntry> = emptyList(),
    ) = StructureNode(StructureNodeKind.ENTITY, parent, retired, name, "ACME", profile, v)

    private fun unit(
        parent: String?,
        v: Long,
        name: String,
    ) = node(parent, v, name).copy(kind = StructureNodeKind.BUSINESS_UNIT)

    private fun org(
        ref: String,
        change: StructureChange,
        node: StructureNode,
        tenant: TenantId = TENANT_A,
    ) = OrganisationStructureChanged(tenant, ref, change, node)

    private fun held(ref: String = "e1") = (replica.node(TENANT_A, ref, deadline(clock)) as Lookup.Local).value

    private fun entry(
        attribute: String,
        value: String,
        from: String,
    ) = ProfileEntry(attribute, value, LocalDate.parse(from))

    @Test
    fun `applies both events and serves them without calling tenancy-svc`() {
        feed.publish(org("e1", StructureChange.CREATED, node("g1", 1)))
        val profile = listOf(entry("uen", "123", "2026-10-01"))
        feed.publish(EntityProfileChanged(TENANT_A, "e1", node("g1", 2, profile = profile)))
        val got = replica.node(TENANT_A, "e1", deadline(clock))
        got.shouldBeInstanceOf<Lookup.Local<StructureNode>>()
        got.value shouldBe node("g1", 2, profile = profile)
        tenancy.calls shouldBe 0
    }

    @Test
    fun `a move and a retirement replace the node`() {
        feed.publish(org("e1", StructureChange.CREATED, node("g1", 1)))
        feed.publish(org("e1", StructureChange.MOVED, node("g2", 2)))
        feed.publish(org("e1", StructureChange.RETIRED, node("g2", 3, retired = true)))
        held().parentRef shouldBe "g2"
        held().retired shouldBe true
    }

    @Test
    fun `a profile change arriving before CREATED keeps the parent`() {
        val profile = listOf(entry("legalName", "Acme Pte Ltd", "2026-10-01"))
        feed.publish(EntityProfileChanged(TENANT_A, "e1", node("g1", 6, profile = profile)))
        feed.publish(org("e1", StructureChange.CREATED, node("g1", 5)))
        held().parentRef shouldBe "g1"
        held().profile shouldBe profile
    }

    @Test
    fun `the order between the two topics never loses attributes`() {
        val profile = listOf(entry("filingFrequency", "QUARTERLY", "2026-10-01"))
        feed.publish(org("e1", StructureChange.MOVED, node("g2", 6, profile = profile)))
        feed.publish(EntityProfileChanged(TENANT_A, "e1", node("g1", 5, profile = profile)))
        held() shouldBe node("g2", 6, profile = profile)
    }

    @Test
    fun `a fill keeps its returned version and a delayed event after it is ignored`() {
        tenancy.answer = { FallbackResult.Found(node("g-fill", 7)) }
        replica.node(TENANT_A, "e1", deadline(clock)) shouldBe Lookup.Filled(node("g-fill", 7))
        feed.publish(org("e1", StructureChange.MOVED, node("g-old", 5)))
        held().parentRef shouldBe "g-fill"
        feed.publish(org("e1", StructureChange.MOVED, node("g-new", 8)))
        held().parentRef shouldBe "g-new"
        tenancy.calls shouldBe 1
    }

    @Test
    fun `an older fill never overwrites a newer node`() {
        val key = StructureKey(TENANT_A, "e1")
        // a replica that has never synced calls its fallback even for a held key
        val bounded =
            EventFedReplica<StructureKey, StructureNode>(
                "structure",
                clock,
                lagBound = Duration.ZERO,
                fallback = { _, _ -> FallbackResult.Found(node("g-fill", 3)) },
                versionOf = StructureNode::structureVersion,
            )
        bounded.apply(ReplicaChange.Upsert(key, node("g-event", 4), 4))
        bounded.get(key, deadline(clock)) shouldBe Lookup.Filled(node("g-event", 4))
        bounded.peek(key) shouldBe node("g-event", 4)
    }

    @Test
    fun `a profile attribute is served for the date asked, a future-dated one not before its date`() {
        val profile =
            listOf(
                entry("filingFrequency", "QUARTERLY", "2026-01-01"),
                entry("filingFrequency", "MONTHLY", "2027-01-01"),
                entry("legalName", "Acme Pte Ltd", "2026-01-01"),
            )
        feed.publish(EntityProfileChanged(TENANT_A, "e1", node("g1", 1, profile = profile)))
        held().attribute("filingFrequency", LocalDate.parse("2026-12-31")) shouldBe "QUARTERLY"
        held().attribute("filingFrequency", LocalDate.parse("2027-01-01")) shouldBe "MONTHLY"
        held().attribute("legalName", LocalDate.parse("2025-12-31")) shouldBe null
        held().profileOn(LocalDate.parse("2026-06-30")) shouldBe
            mapOf("filingFrequency" to "QUARTERLY", "legalName" to "Acme Pte Ltd")
    }

    @Test
    fun `an UPDATED rename replaces the node's name, an unknown changeType applies the full node`() {
        feed.publish(org("bu1", StructureChange.CREATED, unit("e1", 1, "Sales")))
        feed.publish(org("bu1", StructureChange.UPDATED, unit("e1", 2, "Sales APAC")))
        held("bu1").name shouldBe "Sales APAC"
        StructureChange.of("MERGED") shouldBe StructureChange.UNKNOWN
        feed.publish(org("bu1", StructureChange.of("MERGED"), unit("e2", 3, "Sales Asia")))
        held("bu1") shouldBe unit("e2", 3, "Sales Asia")
    }

    @Test
    fun `a miss is filled by a call to tenancy-svc, tenants are isolated`() {
        feed.publish(org("e1", StructureChange.CREATED, node("g1", 1)))
        replica.node(TENANT_B, "e1", deadline(clock)).shouldBeInstanceOf<Lookup.Filled<StructureNode>>()
        tenancy.calls shouldBe 1
    }

    @Test
    fun `keeps serving its last state when tenancy-svc is down`() {
        tenancy.answer = { FallbackResult.Unavailable }
        feed.publish(org("e1", StructureChange.CREATED, node("g1", 1)))
        feed.drop()
        clock.advance(Duration.ofHours(1))
        replica.node(TENANT_A, "e1", deadline(clock)).shouldBeInstanceOf<Lookup.Local<StructureNode>>()
        replica.node(TENANT_A, "never-seen", deadline(clock)) shouldBe Lookup.Unavailable
    }

    @Test
    fun `an out-of-date redelivery changes nothing`() {
        feed.publish(org("e1", StructureChange.MOVED, node("g2", 5)))
        feed.publish(org("e1", StructureChange.CREATED, node("g1", 1)))
        held().parentRef shouldBe "g2"
    }
}
