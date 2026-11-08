package com.dynokore.gst.transport.application

import com.dynokore.gst.transport.domain.AcknowledgementRoute
import com.dynokore.gst.transport.domain.ConnectionRouteState
import com.dynokore.gst.transport.domain.EntityNode
import com.dynokore.gst.transport.domain.EntityRef
import com.dynokore.gst.transport.domain.IssuanceModeEntry
import com.dynokore.gst.transport.domain.TenantRef
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class SetConnectionEntitiesTest {
    private val t = TenantRef("t-1")
    private val clock = Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC)
    private val rows = mutableMapOf<Pair<TenantRef, String>, ConnectionRouteState>()
    private var saves = 0
    private val published = mutableListOf<List<Any?>>()
    private val replica = mutableMapOf<EntityRef, com.dynokore.gst.transport.domain.IssuanceReplicaRow>()

    private val store =
        object : AccessPointConnectionStore {
            override fun load(
                tenant: TenantRef,
                connectionRef: String,
            ) = rows[tenant to connectionRef]

            override fun save(
                tenant: TenantRef,
                connectionRef: String,
                state: ConnectionRouteState,
            ) {
                saves++
                rows[tenant to connectionRef] = state
            }
        }
    private val publisher =
        object : RouteChangePublisher {
            override fun publish(
                tenant: TenantRef,
                connectionRef: String,
                route: AcknowledgementRoute?,
                entityRefs: List<EntityRef>,
                version: Long,
            ) {
                published += listOf(connectionRef, entityRefs, version)
            }
        }
    private val issuance =
        object : IssuanceReplica {
            override fun find(entity: EntityRef) = replica[entity]

            override fun replace(
                entity: EntityRef,
                row: com.dynokore.gst.transport.domain.IssuanceReplicaRow,
            ) {
                replica[entity] = row
            }
        }
    private val index: ConnectionAssignments =
        object : ConnectionAssignments {
            override fun otherConnectionHolding(
                tenant: TenantRef,
                entity: EntityRef,
                connectionRef: String,
            ): String? =
                rows.entries
                    .firstOrNull { (k, v) -> k.first == tenant && k.second != connectionRef && entity in v.entityRefs }
                    ?.key
                    ?.second
        }

    private val useCase: SetConnectionEntities =
        SetConnectionEntities(
            RouteChangeRecorder(
                store,
                publisher,
                object : RouteUnitOfWork {
                    override fun <T> run(block: () -> T): T = block()
                },
            ),
            index,
            EntityRemovalGuard(issuance, clock),
        )

    private fun mode(
        entity: String,
        value: String,
    ) {
        ApplyEntityProfileChanged(issuance).apply(
            t,
            EntityRef(entity),
            EntityNode(1, listOf(IssuanceModeEntry(value, LocalDate.parse("2026-01-01")))),
        )
    }

    init {
        rows[t to "C1"] = ConnectionRouteState(null, listOf(EntityRef("E-1"), EntityRef("E-2")), 3)
        rows[t to "C2"] = ConnectionRouteState(null, listOf(EntityRef("E-9")), 1)
        mode("E-1", "RELAY")
        mode("E-2", "OBSERVE")
    }

    @Test
    fun `an entity held by another connection of the tenant is refused and nothing is recorded or published`() {
        shouldThrow<EntityAssignedToOtherConnection> { useCase.set(t, "C1", listOf("E-1", "E-2", "E-9")) }
        saves shouldBe 0
        published.size shouldBe 0
    }

    @Test
    fun `removing an Observe entity or one with no replica row is refused and nothing is recorded`() {
        shouldThrow<ObserveGuardRefused> { useCase.set(t, "C1", listOf("E-1")) }.code shouldBe "SUB-019"
        rows[t to "C1"] = ConnectionRouteState(null, listOf(EntityRef("E-1"), EntityRef("E-3")), 3)
        shouldThrow<ObserveGuardRefused> { useCase.set(t, "C1", listOf("E-1")) }.code shouldBe "SUB-019"
        saves shouldBe 0
        published.size shouldBe 0
    }

    @Test
    fun `removing a non-Observe entity is accepted and published with a version one higher`() {
        useCase.set(t, "C1", listOf("E-2")) shouldBe RouteOutcome.CHANGED
        published shouldBe listOf(listOf("C1", listOf(EntityRef("E-2")), 4L))
        rows.getValue(t to "C1").entityRefs shouldBe listOf(EntityRef("E-2"))
    }

    @Test
    fun `the same list again is a no-op that publishes nothing`() {
        useCase.set(t, "C1", listOf("E-1", "E-2")) shouldBe RouteOutcome.NO_OP
        saves shouldBe 0
        published.size shouldBe 0
    }

    @Test
    fun `a list with a repeated entity ref fails validation before anything is read`() {
        shouldThrow<DuplicateEntityRef> { useCase.set(t, "C1", listOf("E-1", "E-1")) }
        shouldThrow<DuplicateEntityRef> { useCase.set(t, "NOPE", listOf("E-1", "E-1")) }
        saves shouldBe 0
    }

    @Test
    fun `an unknown connection is not found`() {
        shouldThrow<ConnectionNotFound> { useCase.set(t, "NOPE", listOf("E-1")) }
    }
}
