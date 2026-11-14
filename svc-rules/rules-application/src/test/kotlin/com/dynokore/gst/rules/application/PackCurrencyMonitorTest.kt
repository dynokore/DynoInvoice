package com.dynokore.gst.rules.application

import com.dynokore.gst.rules.domain.pack.PackCurrencyState
import com.dynokore.gst.rules.domain.pack.SilenceLimit
import com.dynokore.gst.rules.domain.pack.UnactivatedPack
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference

/** DEP-001 on the injected clock (INV-REQ-1080, INV-REQ-2468) and the feed poller (INV-REQ-1075). */
class PackCurrencyMonitorTest {
    private class MovableClock(
        start: Instant,
    ) : Clock() {
        val now = AtomicReference(start)

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = now.get()

        fun advanceDays(days: Long) = now.updateAndGet { it.plus(Duration.ofDays(days)) }
    }

    private class FakeStore : PackCurrencyStore {
        var stored: StoredCurrency? = null
        var pending: UnactivatedPack? = null
        val published = mutableListOf<PackCurrencyState>()
        var writes = 0

        override fun load() = stored

        override fun unactivated() = pending

        override fun markReached(day: LocalDate) {
            writes++
            stored = StoredCurrency(day, stored?.published, stored?.evaluatedOn)
        }

        override fun record(
            day: LocalDate,
            state: PackCurrencyState,
            publish: Boolean,
        ) {
            writes++
            if (publish) published += state
            stored = StoredCurrency(stored?.lastReachedOn ?: day, if (publish) state else stored?.published, day)
        }
    }

    private val clock = MovableClock(Instant.parse("2026-11-01T08:00:00Z"))
    private val store = FakeStore()
    private var limit = SilenceLimit.DEFAULT
    private val monitor = PackCurrencyMonitor(store, { limit }, clock)

    @Test
    fun `INV-AC-2497 silence of 7 days raises nothing and the eighth day publishes DEP-001 raised`() {
        monitor.tick()
        repeat(7) {
            clock.advanceDays(1)
            monitor.tick()
        }
        store.published shouldBe emptyList()
        clock.advanceDays(1)
        monitor.tick()
        store.published.single().raised shouldBe true
        store.published.single().daysSinceLastReached shouldBe 8
    }

    @Test
    fun `INV-AC-2383 an installation limit of 30 days moves the raise to the thirty-first day`() {
        limit = SilenceLimit.ofPolicyValue("30")
        monitor.tick()
        repeat(30) {
            clock.advanceDays(1)
            monitor.tick()
        }
        store.published shouldBe emptyList()
        clock.advanceDays(1)
        monitor.tick()
        store.published.single().daysSinceLastReached shouldBe 31
    }

    @Test
    fun `INV-AC-2382 INV-AC-2075 the tuple is published when daysToEffectiveDate changes and not when it is equal`() {
        store.pending = UnactivatedPack("SCOPE-pack", "2026.12", LocalDate.of(2026, 11, 20))
        monitor.tick()
        store.published.single().daysToEffectiveDate shouldBe 19
        monitor.tick() // the same day again: evaluated once per calendar day
        monitor.packActivated() // same tuple: no second event
        store.published.size shouldBe 1
        clock.advanceDays(1)
        monitor.tick()
        store.published.map { it.daysToEffectiveDate } shouldBe listOf(19, 18)
        // activation clears the condition: one more event, raised false
        store.pending = null
        monitor.packActivated()
        store.published.last().raised shouldBe false
        store.published.size shouldBe 3
    }

    @Test
    fun `INV-AC-2382 a tick on the same calendar day writes nothing`() {
        monitor.tick()
        val writes = store.writes
        clock.now.updateAndGet { it.plus(Duration.ofHours(5)) }
        monitor.tick()
        store.writes shouldBe writes
    }

    @Test
    fun `INV-AC-2497 an import restarts the silence clock`() {
        monitor.tick()
        repeat(6) {
            clock.advanceDays(1)
            monitor.tick()
        }
        monitor.bundleImported()
        repeat(7) {
            clock.advanceDays(1)
            monitor.tick()
        }
        store.published shouldBe emptyList()
    }

    @Test
    fun `INV-AC-22191 a poll of an unreachable feed writes nothing and a reachable one restarts the silence clock`() {
        monitor.tick()
        var reachable = false
        val poller = PackFeedPoller({ reachable }, monitor, clock, Duration.ofHours(1))
        val before = store.writes
        poller.tick() shouldBe true
        store.writes shouldBe before
        // inside the interval nothing is polled; after it the poll runs again, still without a write
        poller.tick() shouldBe false
        clock.now.updateAndGet { it.plus(Duration.ofHours(2)) }
        poller.tick() shouldBe true
        store.writes shouldBe before
        // a probe that throws is an unreachable feed, not an error of the data plane
        val throwing = PackFeedPoller({ error("connection refused") }, monitor, clock, Duration.ofHours(1))
        throwing.tick() shouldBe true
        store.writes shouldBe before
        reachable = true
        clock.now.updateAndGet { it.plus(Duration.ofHours(2)) }
        poller.tick() shouldBe true
        store.writes shouldBe before + 1
    }
}
