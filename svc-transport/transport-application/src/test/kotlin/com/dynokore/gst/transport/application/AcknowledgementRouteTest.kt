package com.dynokore.gst.transport.application

import com.dynokore.gst.transport.domain.AcknowledgementRoute
import com.dynokore.gst.transport.domain.ConnectionRouteState
import com.dynokore.gst.transport.domain.EntityNode
import com.dynokore.gst.transport.domain.EntityRef
import com.dynokore.gst.transport.domain.InvalidAcknowledgementRoute
import com.dynokore.gst.transport.domain.IssuanceModeEntry
import com.dynokore.gst.transport.domain.IssuanceReplicaRow
import com.dynokore.gst.transport.domain.RouteChange
import com.dynokore.gst.transport.domain.TenantRef
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class AcknowledgementRouteTest {
    private val t = TenantRef("t-1")
    private val e1 = EntityRef("E-1")
    private val clock = Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC)

    private class Store(
        var failing: Boolean = false,
    ) : AccessPointConnectionStore {
        val rows = mutableMapOf<Pair<TenantRef, String>, ConnectionRouteState>()
        var saves = 0

        override fun load(
            tenant: TenantRef,
            connectionRef: String,
        ) = rows[tenant to connectionRef]

        override fun save(
            tenant: TenantRef,
            connectionRef: String,
            state: ConnectionRouteState,
        ) {
            check(!failing) { "store down" }
            saves++
            rows[tenant to connectionRef] = state
        }
    }

    private class Publisher : RouteChangePublisher {
        val calls = mutableListOf<List<Any?>>()

        override fun publish(
            tenant: TenantRef,
            connectionRef: String,
            route: AcknowledgementRoute?,
            entityRefs: List<EntityRef>,
            version: Long,
        ) {
            calls += listOf(connectionRef, route, entityRefs, version)
        }
    }

    private class Replica : IssuanceReplica {
        val rows = mutableMapOf<EntityRef, IssuanceReplicaRow>()
        var replaces = 0

        override fun find(entity: EntityRef) = rows[entity]

        override fun replace(
            entity: EntityRef,
            row: IssuanceReplicaRow,
        ) {
            replaces++
            rows[entity] = row
        }
    }

    private val store = Store()
    private val pub = Publisher()
    private val replica = Replica()
    private val recorder =
        RouteChangeRecorder(
            store,
            pub,
            object : RouteUnitOfWork {
                override fun <T> run(block: () -> T): T = block()
            },
        )
    private val elect = ElectAcknowledgementRoute(recorder)
    private val clear = ClearAcknowledgementRoute(recorder, replica, clock)

    init {
        store.rows[t to "C1"] = ConnectionRouteState(null, listOf(e1), 1)
        store.rows[t to "C2"] = ConnectionRouteState(AcknowledgementRoute.AP_CALLBACK, listOf(EntityRef("E-2")), 1)
    }

    private fun stored(c: String) = store.rows.getValue(t to c)

    private fun mode(
        entity: EntityRef,
        value: String,
        version: Long,
    ) = ApplyEntityProfileChanged(replica).apply(
        t,
        entity,
        EntityNode(version, listOf(IssuanceModeEntry(value, LocalDate.parse("2026-01-01")))),
    )

    @Test
    fun `a change publishes once with the new version`() {
        elect.elect(t, "C1", "POLLING") shouldBe RouteOutcome.CHANGED
        pub.calls shouldBe listOf(listOf("C1", AcknowledgementRoute.POLLING, listOf(e1), 2L))
        stored("C1").version shouldBe 2L
    }

    @Test
    fun `a no-op publishes nothing`() {
        elect.elect(t, "C2", "AP_CALLBACK") shouldBe RouteOutcome.NO_OP
        clear.clear(t, "C1") shouldBe RouteOutcome.NO_OP
        pub.calls.size shouldBe 0
        store.saves shouldBe 0
    }

    @Test
    fun `a failing store publishes nothing`() {
        store.failing = true
        shouldThrow<IllegalStateException> { elect.elect(t, "C1", "POLLING") }
        pub.calls.size shouldBe 0
    }

    @Test
    fun `an unknown connectionRef stores and publishes nothing`() {
        shouldThrow<ConnectionNotFound> { elect.elect(t, "NOPE", "POLLING") }
        shouldThrow<ConnectionNotFound> { elect.elect(TenantRef("t-2"), "C1", "POLLING") }
        shouldThrow<ConnectionNotFound> { clear.clear(t, "NOPE") }
        pub.calls.size shouldBe 0
        store.saves shouldBe 0
    }

    @Test
    fun `a fourth value is refused and nothing changes`() {
        shouldThrow<InvalidAcknowledgementRoute> { elect.elect(t, "C1", "FOURTH_VALUE") }
        shouldThrow<InvalidAcknowledgementRoute> { elect.elect(t, "C1", "polling") }
        pub.calls.size shouldBe 0
        stored("C1").route shouldBe null
    }

    @Test
    fun `entity refs change publishes through the one function`() {
        val s = recorder.load(t, "C1")
        recorder.record(t, "C1", s.withEntityRefs(listOf(e1, EntityRef("E-9")))) shouldBe RouteOutcome.CHANGED
        pub.calls.single()[3] shouldBe 2L
        val again = recorder.load(t, "C1").withEntityRefs(listOf(e1, EntityRef("E-9")))
        recorder.record(t, "C1", again) shouldBe RouteOutcome.NO_OP
        pub.calls.size shouldBe 1
        (recorder.load(t, "C1").entityRefs.size) shouldBe 2
        (RouteChange.NoOp(s).state) shouldBe s
    }

    @Test
    fun `an Observe entity on the connection refuses the clear, then a later event accepts it`() {
        store.rows[t to "C1"] = ConnectionRouteState(AcknowledgementRoute.AP_CALLBACK, listOf(e1), 1)
        mode(e1, "OBSERVE", 1)
        val refused = shouldThrow<ObserveGuardRefused> { clear.clear(t, "C1") }
        refused.code shouldBe "SUB-023"
        refused.refusal.connection shouldBe "C1"
        stored("C1").route shouldBe AcknowledgementRoute.AP_CALLBACK
        pub.calls.size shouldBe 0
        mode(e1, "RELAY", 2)
        clear.clear(t, "C1") shouldBe RouteOutcome.CHANGED
        stored("C1").route shouldBe null
    }

    @Test
    fun `an Observe entity of a different connection does not block`() {
        store.rows[t to "C1"] = ConnectionRouteState(AcknowledgementRoute.POLLING, listOf(e1), 1)
        mode(EntityRef("E-2"), "OBSERVE", 1)
        mode(e1, "RELAY", 1)
        clear.clear(t, "C1") shouldBe RouteOutcome.CHANGED
    }

    @Test
    fun `an entity with no replica row refuses the clear`() {
        shouldThrow<ObserveGuardRefused> { clear.clear(t, "C2") }
        stored("C2").route shouldBe AcknowledgementRoute.AP_CALLBACK
    }

    @Test
    fun `removal of an Observe entity is refused and of a non-Observe entity accepted`() {
        val guard = EntityRemovalGuard(replica, clock)
        mode(e1, "OBSERVE", 1)
        mode(EntityRef("E-3"), "RELAY", 1)
        shouldThrow<ObserveGuardRefused> { guard.check("C1", e1) }.code shouldBe "SUB-019"
        guard.check("C1", EntityRef("E-3"))
    }

    @Test
    fun `redelivery, an older and an equal event leave the row unchanged`() {
        mode(e1, "OBSERVE", 5)
        val before = replica.rows[e1]
        mode(e1, "OBSERVE", 5)
        mode(e1, "RELAY", 4)
        mode(e1, "RELAY", 5)
        replica.rows[e1] shouldBe before
        replica.replaces shouldBe 1
    }

    @Test
    fun `an event without node or without an issuanceMode entry leaves the row unchanged`() {
        mode(e1, "OBSERVE", 5)
        val apply = ApplyEntityProfileChanged(replica)
        apply.apply(t, e1, null)
        apply.apply(t, e1, EntityNode(9, emptyList()))
        replica.replaces shouldBe 1
        replica.rows.getValue(e1).structureVersion shouldBe 5L
    }

    @Test
    fun `a future-dated Observe entry blocks only from its date`() {
        ApplyEntityProfileChanged(replica).apply(
            t,
            e1,
            EntityNode(1, listOf(IssuanceModeEntry("OBSERVE", LocalDate.parse("2026-07-02")))),
        )
        store.rows[t to "C1"] = ConnectionRouteState(AcknowledgementRoute.POLLING, listOf(e1), 1)
        // today 2026-07-01: no mode in force yet, so the row exists and the entity is not Observe
        clear.clear(t, "C1") shouldBe RouteOutcome.CHANGED
        store.rows[t to "C1"] = ConnectionRouteState(AcknowledgementRoute.POLLING, listOf(e1), 3)
        val nextDay = Clock.fixed(Instant.parse("2026-07-02T00:00:00Z"), ZoneOffset.UTC)
        val onDate = ClearAcknowledgementRoute(recorder, replica, nextDay)
        shouldThrow<ObserveGuardRefused> { onDate.clear(t, "C1") }
    }
}
