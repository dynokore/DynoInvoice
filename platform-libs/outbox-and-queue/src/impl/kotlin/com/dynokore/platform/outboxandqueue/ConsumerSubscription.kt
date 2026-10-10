package com.dynokore.platform.outboxandqueue

/** Which consumer reads which topic, and the schema holding its inbox, sequences and offset. */
data class ConsumerSubscription(
    val consumer: String,
    val topic: String,
    val schema: String,
)
