package com.dynokore.gst.rules.adapters.packs

import com.dynokore.platform.serviceruntime.replica.EntityProfileChanged
import com.dynokore.platform.serviceruntime.replica.ProfileEntry
import com.dynokore.platform.serviceruntime.replica.StructureNode
import com.dynokore.platform.serviceruntime.replica.StructureNodeKind
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** The rate-source block of one tenant over a fake replica and a fake scope pack (INV-REQ-504, INV-Q-16533 A). */
class ReplicaRateSourcePortTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
    private val other = TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000a2"))
    private val clock = Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC)
    private val day = LocalDate.of(2027, 1, 1)
    private val replica = StructureReplica(clock).also { it.onSnapshotComplete(1) }

    private fun source(
        id: String,
        sourceClass: String,
    ) = """{"sourceId":"$id","name":"n","bases":["b"],"sourceClass":"$sourceClass","sourceReference":"r"}"""

    private fun pack(vararg sources: Pair<String, String>) =
        """{"exchangeRateSources":[${sources.joinToString(",") { source(it.first, it.second) }}]}"""

    private val standard = pack("RS-I" to "INFERRED", "RS-P" to "PRIMARY", "RS-S" to "SECONDARY", "RS-J" to "INFERRED")

    private fun elect(
        ref: String,
        value: String?,
        on: LocalDate = day,
        owner: TenantId = tenant,
        retired: Boolean = false,
        attribute: String = ReplicaRateSourcePort.ATTRIBUTE,
        kind: StructureNodeKind = StructureNodeKind.ENTITY,
    ) = replica.accept(
        EntityProfileChanged(
            owner,
            ref,
            StructureNode(
                kind,
                null,
                retired,
                "E",
                ref,
                listOfNotNull(value?.let { ProfileEntry(attribute, it, on) }),
                1,
            ),
        ),
    )

    private fun port(
        contents: String? = standard,
        structure: StructureReplica = replica,
    ) = ReplicaRateSourcePort(structure) { _, _ -> contents?.toByteArray() }

    private fun blocking(
        asOf: LocalDate = day,
        who: TenantId = tenant,
    ) = port().blocking(who, asOf)

    @Test
    fun `a tenant with no entity holding the attribute gives the empty set`() {
        elect("E1", null)
        blocking() shouldBe emptyList()
        ReplicaRateSourcePort(replica) { _, _ -> standard.toByteArray() }.blocking(other, day) shouldBe emptyList()
    }

    @Test
    fun `an entity electing an INFERRED id gives that id`() {
        elect("E1", "RS-I")
        blocking() shouldBe listOf("RS-I")
    }

    @Test
    fun `an id listed PRIMARY or SECONDARY gives the empty set`() {
        elect("E1", "RS-P")
        elect("E2", "RS-S")
        blocking() shouldBe emptyList()
    }

    @Test
    fun `an elected id that is not on the list is not reported`() {
        elect("E1", "RS-X")
        blocking() shouldBe emptyList()
    }

    @Test
    fun `two entities electing the same id give it once`() {
        elect("E1", "RS-I")
        elect("E2", "RS-I")
        elect("E3", "RS-J")
        blocking() shouldBe listOf("RS-I", "RS-J")
    }

    @Test
    fun `an election whose effective date is after asOf is not counted`() {
        elect("E1", "RS-I", on = day.plusDays(1))
        blocking() shouldBe emptyList()
        blocking(day.plusDays(1)) shouldBe listOf("RS-I")
    }

    @Test
    fun `the entity of another tenant is not counted`() {
        elect("E1", "RS-I", owner = other)
        blocking() shouldBe emptyList()
        blocking(who = other) shouldBe listOf("RS-I")
    }

    @Test
    fun `a retired entity node is not counted`() {
        elect("E1", "RS-I", retired = true)
        blocking() shouldBe emptyList()
    }

    @Test
    fun `a node that is not an entity is not counted`() {
        elect("U1", "RS-I", kind = StructureNodeKind.BUSINESS_UNIT)
        blocking() shouldBe emptyList()
    }

    @Test
    fun `the attribute name exchangeRateSources is not read`() {
        elect("E1", "RS-I", attribute = "exchangeRateSources")
        elect("E2", "RS-I", attribute = "ExchangeRateSource")
        blocking() shouldBe emptyList()
    }

    @Test
    fun `a replica that is not synced gives null, not the empty set`() {
        val cold = StructureReplica(clock)
        val node = StructureNode(StructureNodeKind.ENTITY, null, false, "E", "E1", emptyList(), 1)
        cold.accept(EntityProfileChanged(tenant, "E1", node))
        port(structure = cold).blocking(tenant, day) shouldBe null
        replica.onDisconnected()
        blocking() shouldBe null
    }

    @Test
    fun `no scope pack in force gives null`() {
        elect("E1", "RS-I")
        port(contents = null).blocking(tenant, day) shouldBe null
    }

    @Test
    fun `contents that do not decode give null`() {
        elect("E1", "RS-I")
        listOf(
            "not json",
            "{}",
            """{"exchangeRateSources":[]}""",
            """{"exchangeRateSource":[${source("RS-I", "INFERRED")}]}""",
            pack("RS-I" to "GUESSED"),
            pack("RS-I" to "inferred"),
            """{"exchangeRateSources":[{"sourceClass":"INFERRED"}]}""",
            """{"exchangeRateSources":[${source("RS-I", "INFERRED")},{"sourceId":"RS-X","sourceClass":"X"}]}""",
        ).forEach { port(contents = it).blocking(tenant, day) shouldBe null }
    }

    @Test
    fun `no acceptance is read, a look-alike acceptance row of a mapping-pack entry does not clear the source`() {
        elect("E1", "RS-I")
        // an acceptance of a mapping-pack entry with the same text id: the port reads none, so the source stays
        val accepted = standard.dropLast(1) + ""","acceptances":[{"entryId":"RS-I","validUntil":"2099-01-01"}]}"""
        port(contents = accepted).blocking(tenant, day) shouldBe listOf("RS-I")
    }

    @Test
    fun `decode returns the pairs in pack order and null for a singular member or an empty list`() {
        ScopePackRateSources.decode(standard.toByteArray()) shouldBe
            listOf("RS-I" to "INFERRED", "RS-P" to "PRIMARY", "RS-S" to "SECONDARY", "RS-J" to "INFERRED")
        val singular = """{"exchangeRateSource":[${source("A", "PRIMARY")}]}"""
        ScopePackRateSources.decode(singular.toByteArray()) shouldBe null
        ScopePackRateSources.decode("""{"exchangeRateSources":[]}""".toByteArray()) shouldBe null
        ScopePackRateSources.decode(pack("A" to "TERTIARY").toByteArray()) shouldBe null
        val noId = """{"exchangeRateSources":[{"sourceClass":"PRIMARY"}]}"""
        ScopePackRateSources.decode(noId.toByteArray()) shouldBe null
    }
}
