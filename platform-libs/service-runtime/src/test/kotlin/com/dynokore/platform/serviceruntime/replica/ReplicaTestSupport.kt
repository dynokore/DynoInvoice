package com.dynokore.platform.serviceruntime.replica

import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** A clock the test advances (ARC-13: time is always injected). */
class MutableClock(private var now: Instant = Instant.parse("2026-10-11T00:00:00Z")) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = now

    fun advance(by: Duration) {
        now = now.plus(by)
    }
}

val TENANT_A = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
val TENANT_B = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000b"))

fun deadline(clock: Clock) = Deadline.after(Duration.ofSeconds(30), clock)

/** A scripted fallback: counts calls and answers (or fails) as told. */
class FakeFallback<K, V>(var answer: (K) -> FallbackResult<V>) : ReplicaFallback<K, V> {
    var calls = 0
        private set

    override fun fetch(
        key: K,
        deadline: Deadline,
    ): FallbackResult<V> {
        calls++
        return answer(key)
    }
}

/**
 * A fake feed for any replica: publishes events, heartbeats and a drop, the way a
 * service's event consumer would. Versions are the feed's own monotonic sequence.
 */
class FakeFeed<E>(private val sink: ReplicaFeedSink<E>, synced: Boolean = true) {
    init {
        if (synced) sink.onSnapshotComplete(0)
    }

    fun snapshotComplete(version: Long) = sink.onSnapshotComplete(version)

    fun publish(event: E) = sink.accept(event)

    fun heartbeat(headVersion: Long) = sink.observeHead(headVersion)

    fun drop() = sink.onDisconnected()
}
