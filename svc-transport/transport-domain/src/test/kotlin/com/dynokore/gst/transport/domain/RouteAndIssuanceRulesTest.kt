package com.dynokore.gst.transport.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.LocalDate

class RouteAndIssuanceRulesTest {
    private val e1 = EntityRef("E-1")
    private val t = TenantRef("t-1")
    private val start = ConnectionRouteState(null, listOf(e1), 4)

    @Test
    fun `three changes give versions n, n+1 and n+2`() {
        val a = start.elect(AcknowledgementRoute.POLLING).state
        val b = a.elect(AcknowledgementRoute.REPORT_IMPORT).state
        val c = b.clear().state
        listOf(a.version, b.version, c.version) shouldBe listOf(5L, 6L, 7L)
    }

    @Test
    fun `the same value twice is one change and a no-op`() {
        val first = start.elect(AcknowledgementRoute.AP_CALLBACK)
        first.shouldBeInstanceOf<RouteChange.Changed>()
        val second = first.state.elect(AcknowledgementRoute.AP_CALLBACK)
        second.shouldBeInstanceOf<RouteChange.NoOp>()
        second.state.version shouldBe 5L
    }

    @Test
    fun `clearing twice is one change and a no-op`() {
        val cleared = start.elect(AcknowledgementRoute.POLLING).state.clear()
        cleared.shouldBeInstanceOf<RouteChange.Changed>()
        cleared.state.clear().shouldBeInstanceOf<RouteChange.NoOp>()
        start.clear().state shouldBe start
    }

    @Test
    fun `a fourth value is refused`() {
        shouldThrow<InvalidAcknowledgementRoute> { AcknowledgementRoute.parse("FOURTH_VALUE") }
    }

    @Test
    fun `lower case polling is refused`() {
        shouldThrow<InvalidAcknowledgementRoute> { AcknowledgementRoute.parse("polling") }
        shouldThrow<InvalidAcknowledgementRoute> { AcknowledgementRoute.parse(null) }
        shouldThrow<InvalidAcknowledgementRoute> { AcknowledgementRoute.parse("") }
        AcknowledgementRoute.parse("POLLING") shouldBe AcknowledgementRoute.POLLING
    }

    @Test
    fun `a change of entity refs raises the version by one and the same list is a no-op`() {
        val c = start.withEntityRefs(listOf(e1, EntityRef("E-2")))
        c.shouldBeInstanceOf<RouteChange.Changed>()
        c.state.version shouldBe 5L
        start.withEntityRefs(listOf(e1)).shouldBeInstanceOf<RouteChange.NoOp>()
    }

    private fun entry(
        v: String,
        d: String,
    ) = IssuanceModeEntry(v, LocalDate.parse(d))

    private val row = IssuanceReplicaRow(t, listOf(entry("RELAY", "2026-01-01"), entry("OBSERVE", "2026-06-01")), 3)

    @Test
    fun `a future-dated entry applies on its date and not before`() {
        row.isObserveOn(LocalDate.parse("2026-05-31")) shouldBe false
        row.modeOn(LocalDate.parse("2026-05-31")) shouldBe "RELAY"
        row.isObserveOn(LocalDate.parse("2026-06-01")) shouldBe true
        row.modeOn(LocalDate.parse("2025-12-31")) shouldBe null
    }

    @Test
    fun `equal or lower structureVersion is ignored, higher replaces whole`() {
        val other = listOf(entry("RELAY", "2026-02-01"))
        IssuanceReplicaRow.apply(row, t, EntityNode(3, other)) shouldBe row
        IssuanceReplicaRow.apply(row, t, EntityNode(2, other)) shouldBe row
        IssuanceReplicaRow.apply(row, t, EntityNode(4, other)) shouldBe IssuanceReplicaRow(t, other, 4)
    }

    @Test
    fun `no node or no issuanceMode entry leaves the row unchanged`() {
        IssuanceReplicaRow.apply(row, t, null) shouldBe row
        IssuanceReplicaRow.apply(row, t, EntityNode(9, emptyList())) shouldBe row
        IssuanceReplicaRow.apply(null, t, null) shouldBe null
    }

    @Test
    fun `guard fails closed on a missing row`() {
        val today = LocalDate.parse("2026-07-01")
        ObserveGuard.refusal("C", listOf(e1), { null }, today)!!.entities shouldBe listOf(e1)
        ObserveGuard.refusal("C", listOf(e1), { row }, today)!!.connection shouldBe "C"
        val relay = IssuanceReplicaRow(t, listOf(entry("RELAY", "2026-01-01")), 1)
        ObserveGuard.refusal("C", listOf(e1), { relay }, today) shouldBe null
    }
}
