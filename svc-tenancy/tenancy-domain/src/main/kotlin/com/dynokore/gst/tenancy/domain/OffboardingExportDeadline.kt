package com.dynokore.gst.tenancy.domain

import java.time.Duration
import java.time.Instant

/**
 * True only for a REQUESTED record whose export result is overdue: [now] minus [createdAt] is strictly greater than
 * [limit]. The limit is configuration (12-factor III), never a constant; a terminal state never expires (INV-Q-5025).
 */
fun exportOverdue(
    state: OffboardingState,
    createdAt: Instant,
    now: Instant,
    limit: Duration,
): Boolean = state == OffboardingState.REQUESTED && Duration.between(createdAt, now) > limit
