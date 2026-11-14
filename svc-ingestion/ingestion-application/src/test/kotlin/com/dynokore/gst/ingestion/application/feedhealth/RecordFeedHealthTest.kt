package com.dynokore.gst.ingestion.application.feedhealth

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

class RecordFeedHealthTest {
    private val t = UUID.randomUUID()
    private val t2 = UUID.randomUUID()

    private class Memory :
        FeedHealthStore,
        FeedHealthEvents {
        val states = mutableMapOf<FeedHealthKey, FeedHealthState>()
        val counters = mutableMapOf<FeedHealthKey, Long>()
        val saves = mutableListOf<Pair<FeedHealthKey, FeedHealthState>>()
        val published = mutableListOf<FeedHealthChange>()

        override fun current(key: FeedHealthKey): FeedHealthState? = states[key]

        override fun save(
            key: FeedHealthKey,
            state: FeedHealthState,
        ): Long {
            states[key] = state
            saves += key to state
            return counters.merge(key, 1L, Long::plus)!!
        }

        override fun publish(change: FeedHealthChange) {
            published += change
        }
    }

    private fun key(
        tenant: UUID = t,
        period: String = "2027-01",
        control: FeedControl = FeedControl.COUNT,
    ) = FeedHealthKey(tenant, "F1", "E", period, control)

    @Test
    fun `INV-AC-22213 seven steps give the outcomes and counters`() {
        val m = Memory()
        val uc = RecordFeedHealth(m, m)
        val count = key()
        val value = key(control = FeedControl.VALUE)
        val count2 = key(tenant = t2)
        val steps =
            listOf(
                count to FeedHealthState.FAILED,
                count to FeedHealthState.FAILED,
                count to FeedHealthState.CLEARED,
                count to FeedHealthState.CLEARED,
                value to FeedHealthState.CLEARED,
                value to FeedHealthState.FAILED,
                count2 to FeedHealthState.FAILED,
            )
        val outcomes = steps.map { (k, s) -> uc.record(k, s) }
        val p = FeedHealthOutcome.Published
        val u = FeedHealthOutcome.Unchanged
        outcomes shouldBe listOf(p, u, p, u, u, p, p)
        m.published shouldBe
            listOf(
                FeedHealthChange(count, FeedHealthState.FAILED, 1),
                FeedHealthChange(count, FeedHealthState.CLEARED, 2),
                FeedHealthChange(value, FeedHealthState.FAILED, 1),
                FeedHealthChange(count2, FeedHealthState.FAILED, 1),
            )
        m.saves.size shouldBe 4
        m.states[value] shouldBe FeedHealthState.FAILED
    }

    @Test
    fun `INV-AC-22213 CLEARED with no stored FAILED stores no row and publishes nothing`() {
        val m = Memory()
        RecordFeedHealth(m, m).record(key(control = FeedControl.VALUE), FeedHealthState.CLEARED) shouldBe
            FeedHealthOutcome.Unchanged
        m.states.isEmpty() shouldBe true
        m.saves.isEmpty() shouldBe true
        m.published.isEmpty() shouldBe true
    }

    @Test
    fun `INV-AC-22213 same key with another period has its own counter`() {
        val m = Memory()
        val uc = RecordFeedHealth(m, m)
        uc.record(key(), FeedHealthState.FAILED)
        uc.record(key(), FeedHealthState.CLEARED)
        uc.record(key(period = "2027-02"), FeedHealthState.FAILED)
        m.published.map { it.stateVersion } shouldBe listOf(1L, 2L, 1L)
    }

    @Test
    fun `INV-AC-22213 same key in another tenant has its own counter`() {
        val m = Memory()
        val uc = RecordFeedHealth(m, m)
        uc.record(key(), FeedHealthState.FAILED)
        uc.record(key(), FeedHealthState.CLEARED)
        uc.record(key(tenant = t2), FeedHealthState.FAILED)
        m.published.map { it.stateVersion } shouldBe listOf(1L, 2L, 1L)
    }

    @Test
    fun `INV-AC-22213 conformance fixture is called through the port types`() {
        val m = Memory()
        val store: FeedHealthStore = m
        val events: FeedHealthEvents = m
        store.current(key()) shouldBe null
        store.save(key(), FeedHealthState.FAILED) shouldBe 1L
        events.publish(FeedHealthChange(key(), FeedHealthState.FAILED, 1))
        m.published.size shouldBe 1
    }
}
