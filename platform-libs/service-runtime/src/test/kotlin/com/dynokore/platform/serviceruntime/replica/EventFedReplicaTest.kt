package com.dynokore.platform.serviceruntime.replica

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Duration

/** The generic mechanism (INV-REQ-1199, INV-REQ-1200): measured lag, lag bound, fallback, miss fill, last-known. */
class EventFedReplicaTest {
    private val clock = MutableClock()
    private val ok = FakeFallback<String, String> { FallbackResult.Found("from-owner-$it") }

    private fun replica(
        bound: Duration? = null,
        fallback: ReplicaFallback<String, String>? = ok,
        synced: Boolean = true,
    ) = EventFedReplica("t", clock, bound, fallback).also { if (synced) it.tracker.onSnapshotComplete(0) }

    @Test
    fun `serves a held key locally and never calls the owner`() {
        val r = replica()
        r.apply(ReplicaChange.Upsert("k", "v", 1))
        r.get("k", deadline(clock)) shouldBe Lookup.Local("v", Lag.Measured(Duration.ZERO))
        ok.calls shouldBe 0
    }

    @Test
    fun `a miss is filled by the owner and then held`() {
        val r = replica()
        r.get("k", deadline(clock)) shouldBe Lookup.Filled("from-owner-k")
        r.get("k", deadline(clock)).shouldBeInstanceOf<Lookup.Local<String>>()
        ok.calls shouldBe 1
    }

    @Test
    fun `an unbounded replica keeps serving its last state when the owner is down`() {
        val down = FakeFallback<String, String> { FallbackResult.Unavailable }
        val r = replica(fallback = down)
        r.apply(ReplicaChange.Upsert("k", "v", 1))
        r.tracker.onDisconnected()
        clock.advance(Duration.ofHours(6))
        r.get("k", deadline(clock)).shouldBeInstanceOf<Lookup.Local<String>>()
        r.get("missing", deadline(clock)) shouldBe Lookup.Unavailable
        down.calls shouldBe 1
    }

    @Test
    fun `an owner that has no such key yields Absent and holds nothing`() {
        val r = replica(fallback = FakeFallback<String, String> { FallbackResult.NotFound })
        r.get("k", deadline(clock)) shouldBe Lookup.Absent
        r.peek("k") shouldBe null
    }

    @Test
    fun `a removal is held and answered Absent without a call`() {
        val r = replica()
        r.apply(ReplicaChange.Upsert("k", "v", 1))
        r.apply(ReplicaChange.Remove("k", 2))
        r.get("k", deadline(clock)) shouldBe Lookup.Absent
        ok.calls shouldBe 0
    }

    @Test
    fun `redelivered and stale changes are ignored`() {
        val r = replica()
        r.apply(ReplicaChange.Upsert("k", "new", 5))
        r.apply(ReplicaChange.Upsert("k", "old", 3))
        r.apply(ReplicaChange.Upsert("k", "new", 5))
        r.apply(ReplicaChange.Remove("k", 4))
        r.peek("k") shouldBe "new"
    }

    @Test
    fun `a bounded replica within its bound decides locally, beyond it calls the owner`() {
        val r = replica(bound = Duration.ofSeconds(1))
        val feed = FakeFeed(sinkOf(r))
        r.apply(ReplicaChange.Upsert("k", "v", 1))
        feed.heartbeat(5) // the source is ahead of what was applied
        clock.advance(Duration.ofSeconds(1))
        r.get("k", deadline(clock)).shouldBeInstanceOf<Lookup.Local<String>>() // exactly at the bound: still local
        clock.advance(Duration.ofMillis(1))
        r.get("k", deadline(clock)) shouldBe Lookup.Filled("from-owner-k")
        ok.calls shouldBe 1
    }

    @Test
    fun `a bounded replica beyond its bound with the owner down serves its last known state`() {
        val down = FakeFallback<String, String> { FallbackResult.Unavailable }
        val r = replica(bound = Duration.ofSeconds(1), fallback = down)
        r.apply(ReplicaChange.Upsert("k", "v", 1))
        r.tracker.onDisconnected()
        clock.advance(Duration.ofSeconds(5))
        val got = r.get("k", deadline(clock))
        got.shouldBeInstanceOf<Lookup.LastKnown<String>>()
        got.value shouldBe "v"
        got.lag shouldBe Lag.Measured(Duration.ofSeconds(5))
    }

    @Test
    fun `a replica never in contact has unknown lag and is never within a bound`() {
        val r = replica(bound = Duration.ofDays(1), synced = false)
        r.lag() shouldBe Lag.Unknown
        r.get("k", deadline(clock)) shouldBe Lookup.Filled("from-owner-k")
    }

    @Test
    fun `a fill does not shadow a later event`() {
        val r = replica()
        r.get("k", deadline(clock))
        r.apply(ReplicaChange.Upsert("k", "event", 0))
        r.peek("k") shouldBe "event"
    }

    private fun sinkOf(r: EventFedReplica<String, String>) = object : ReplicaFeedSink<ReplicaChange<String, String>> {
        override fun accept(event: ReplicaChange<String, String>) = r.apply(event)

        override fun observeHead(version: Long) = r.tracker.observeHead(version)

        override fun onSnapshotComplete(version: Long) = r.tracker.onSnapshotComplete(version)

        override fun onDisconnected() = r.tracker.onDisconnected()
    }
}
