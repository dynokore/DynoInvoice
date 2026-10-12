package com.dynokore.platform.outboxandqueue

/**
 * The record-position write of a record topic's keeper (INV-REQ-1205 b4, INV-REQ-3650; coordinator ruling INV-Q-4266):
 * evidence-svc, after each durable commit, records its committed position on a record topic so the event-log purge
 * never removes a record it has not stored. Nothing calls it automatically; the keeper calls it.
 */
fun interface EventLogPositions {
    /**
     * Records [offset], the keeper's committed `log_offset` on [topic], through `eventlog.record_position`, once per
     * call. The position moves forward only: a lower offset leaves it. Returns the position stored after the call.
     *
     * @throws EventBindingUnavailable when the event log refuses it: [topic] is no record topic, or the calling runtime
     *   login is not its keeper, or the event log is unreachable.
     */
    fun record(
        topic: String,
        offset: Long,
    ): Long
}
