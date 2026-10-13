package com.dynokore.platform.serviceruntime.diagnostics

import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The uniform diagnostic endpoint's "recent error codes" (FR-AE.82), kept
 * in-memory per JVM: a running count and last-raised instant per catalogue error
 * code (ARC-13: time from an injected [Clock], never `Instant.now()`). Naturally
 * bounded — the catalogue of error codes is closed (`error-codes.yaml`), so this
 * never grows without limit the way a request-scoped or per-document cache would.
 */
class RecentErrorCodesRecorder(
    private val clock: Clock,
) {
    private val counts = ConcurrentHashMap<String, AtomicLong>()
    private val lastRaisedAt = ConcurrentHashMap<String, Instant>()

    fun record(code: String) {
        counts.computeIfAbsent(code) { AtomicLong(0) }.incrementAndGet()
        lastRaisedAt[code] = clock.instant()
    }

    /** A snapshot of every code raised so far this JVM's lifetime, most recently raised first. */
    fun snapshot(): List<ErrorCodeCount> =
        counts.keys
            .map { code -> ErrorCodeCount(code, counts.getValue(code).get(), lastRaisedAt.getValue(code).toString()) }
            .sortedByDescending { it.lastRaisedAt }
}
