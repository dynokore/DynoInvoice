package com.dynokore.platform.serviceruntime.staleness

import java.time.Duration

/**
 * The staleness thresholds service-runtime ships as defaults: **PROVISIONAL** until the SDS §13.7 performance tests
 * confirm them (INV-Q-2987). The values are the owner's tiered ruling INV-Q-2991: [SUBMISSION_PATH] for
 * payload-svc and submission-svc consuming CanonicalVersionCreated, PayloadReady, PayloadRejected and
 * DocumentReleased, [OTHER] for every other consumer and event type. Each is also the ceiling of that rule: an
 * installation may only tighten it (INV-REQ-1165 b4), so a longer value is refused at start-up
 * ([StalenessRules.resolve]). They are installation settings, the same for every tenant, so not variation points
 * (ARC-12). They travel with the release train; §13.7 replaces them here.
 */
object ProvisionalStalenessThresholds {
    /** Every value of this object is provisional until SDS §13.7 (INV-Q-2987). */
    const val PROVISIONAL: Boolean = true

    /** The submission path (INV-Q-2991): a lag here delays transmission against the due date. */
    val SUBMISSION_PATH: Duration = Duration.ofMinutes(5)

    /** Every other consumer and event type (INV-Q-2991). */
    val OTHER: Duration = Duration.ofMinutes(30)

    /** The consumers on the submission path (INV-Q-2991). */
    val SUBMISSION_PATH_CONSUMERS: Set<String> = setOf("payload-svc", "submission-svc")

    /** The event names on the submission path (INV-Q-2991). */
    val SUBMISSION_PATH_EVENTS: Set<String> =
        setOf("CanonicalVersionCreated", "PayloadReady", "PayloadRejected", "DocumentReleased")

    /** The provisional threshold, and ceiling, of [consumer] for [eventType]. */
    fun of(
        consumer: String,
        eventType: String,
    ): Duration =
        if (consumer in SUBMISSION_PATH_CONSUMERS &&
            eventName(eventType) in SUBMISSION_PATH_EVENTS
        ) {
            SUBMISSION_PATH
        } else {
            OTHER
        }

    /**
     * The event name of a CloudEvents type `com.dynokore.gst.<service>.<EventName>.v<major>` (API-40), or [eventType]
     * itself when it is not of that form.
     */
    internal fun eventName(eventType: String): String {
        val segments = eventType.split('.')
        return if (segments.size >= 2 &&
            VERSION.matches(segments.last())
        ) {
            segments[segments.size - 2]
        } else {
            segments.last()
        }
    }

    private val VERSION = Regex("v\\d+")
}
