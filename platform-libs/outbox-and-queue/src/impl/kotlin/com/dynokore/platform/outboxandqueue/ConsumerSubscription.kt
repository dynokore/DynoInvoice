package com.dynokore.platform.outboxandqueue

/**
 * Which consumer reads which topic, and the schema holding its inbox, sequences and offset; [retry] is how it
 * redelivers a record whose handler failed and [classifier] tells record failures from infrastructure ones.
 *
 * [trackSources] is set for a topic whose catalogue producer is 'every service' (INV-Q-3539): the consumer then keeps
 * the set of sources it has seen there in its consumption state, so a replay addressed to 'every service' completes
 * when a marker from each of them is applied (owner ruling INV-Q-3669). A service's subscriptions to one producer's
 * topics, and its intake of that producer's replay topic, share one [consumer] name: `docsequence` is compared per
 * consumer, so a replayed state is STALE against what the live topics already applied (INV-Q-3603).
 *
 * Record-topic events (`record_topic` in topics.yaml) are applied on inbox dedupe by event id only, with no
 * `docsequence` check and no held-sequence row, live and on dead-letter replay (coordinator ruling INV-Q-3809,
 * INV-REQ-1221 b4). The consumer takes that set from the catalogue itself (`EventCatalogue.recordTypes()`), so no
 * wiring is needed; [recordTypes] is an optional declaration, and a set that differs from the catalogue's fails the
 * consumer's construction.
 */
data class ConsumerSubscription @JvmOverloads constructor(
    val consumer: String,
    val topic: String,
    val schema: String,
    val retry: ConsumerRetryPolicy = ConsumerRetryPolicy.DEFAULT,
    val classifier: FailureClassifier = FailureClassifier.DEFAULT,
    val trackSources: Boolean = false,
    val recordTypes: Set<String>? = null,
)
