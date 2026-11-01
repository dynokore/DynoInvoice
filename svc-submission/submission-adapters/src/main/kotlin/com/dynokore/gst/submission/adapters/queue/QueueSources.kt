package com.dynokore.gst.submission.adapters.queue

import com.dynokore.gst.submission.application.queue.JitterSource
import com.dynokore.gst.submission.application.queue.RetryBoundarySource
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Duration
import java.util.concurrent.ThreadLocalRandom

/**
 * The deployment property `retry-boundary-minutes` as the retry boundary of every connection (INV-Q-14316 A, until the
 * tenant values are read through PolicyReader). A zero or negative value fails the start.
 */
class ConfiguredRetryBoundary(
    minutes: Int,
) : RetryBoundarySource {
    private val boundary: Duration

    init {
        require(minutes > 0) { "retry-boundary-minutes must be a positive integer: $minutes" }
        boundary = Duration.ofMinutes(minutes.toLong())
    }

    override fun boundary(
        tenant: TenantId,
        entityRef: String,
    ): Duration = boundary
}

/** A uniform draw in [0, 1) (INV-Q-14328 A). */
class RandomJitter : JitterSource {
    override fun draw(): Double = ThreadLocalRandom.current().nextDouble()
}
