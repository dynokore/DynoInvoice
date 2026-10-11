package com.dynokore.platform.outboxandqueue

/**
 * Which consumer reads which topic, and the schema holding its inbox, sequences and offset; [retry] is how it
 * redelivers a record whose handler failed and [classifier] tells record failures from infrastructure ones.
 */
data class ConsumerSubscription @JvmOverloads constructor(
    val consumer: String,
    val topic: String,
    val schema: String,
    val retry: ConsumerRetryPolicy = ConsumerRetryPolicy.DEFAULT,
    val classifier: FailureClassifier = FailureClassifier.DEFAULT,
)
