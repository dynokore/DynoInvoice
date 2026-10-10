package com.dynokore.platform.outboxandqueue

import java.time.Duration

/**
 * The priority lanes of the queue (INV-REQ-1072, FR-C.8). Declaration order is drain order: a lane is served only
 * when every lane before it has nothing claimable, so reprocessing, backfill and replay never dequeue ahead of
 * live or deadline-near work.
 */
enum class Lane {
    /** A document approaching its due date: drains first. */
    DEADLINE_NEAR,

    /** Routine live submission. */
    LIVE,

    /** Reprocessing after a pack activation or correction. */
    REPROCESSING,

    /** Backfill of an earlier period. */
    BACKFILL,

    /** Replay of dead letters to a named consumer. */
    REPLAY,
}

/** At most [permits] claims per [window]. */
data class RateLimit(
    val permits: Int,
    val window: Duration,
) {
    init {
        require(permits >= 1) { "permits must be at least 1" }
        require(!window.isZero && !window.isNegative) { "window must be positive" }
    }
}

/**
 * The independent rate limit of each lane. A lane without an entry is unlimited. Each limit is its own
 * allowance: a claim in one lane never draws on another's (INV-AC-2097, INV-AC-2104).
 */
class LaneLimits(
    private val limits: Map<Lane, RateLimit> = emptyMap(),
) {
    fun of(lane: Lane): RateLimit? = limits[lane]
}
