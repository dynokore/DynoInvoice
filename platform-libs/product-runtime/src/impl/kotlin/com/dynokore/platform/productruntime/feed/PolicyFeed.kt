package com.dynokore.platform.productruntime.feed

import com.dynokore.platform.outboxandqueue.LogRecord
import com.dynokore.platform.serviceruntime.events.PlatformEventReader
import com.dynokore.platform.serviceruntime.replica.PolicyFeedSink

/**
 * Feeds `PolicyChanged` into the policy replica (INV-REQ-1241 b2, AD-26). PolicyChanged is service-runtime's own
 * platform event, read by its tolerant [PlatformEventReader] (INV-AC-2148, coordinator ruling INV-Q-3852); the
 * replica orders each value by its topic and offset, which only the log record carries (INV-T-3002).
 */
class PolicyFeed(
    private val sink: PolicyFeedSink,
    private val reader: PlatformEventReader = PlatformEventReader(),
) {
    /** Applies one record of [topic]; a record that does not meet the contract throws a ContractViolation. */
    fun accept(
        topic: String,
        record: LogRecord,
    ) = sink.accept(reader.policyChanged(topic, record))
}
