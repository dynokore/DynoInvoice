package com.dynokore.gst.rules.adapters.events

import com.dynokore.gst.rules.application.PackEvent
import com.dynokore.gst.rules.application.PackEventEmitter
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboxWriter

/**
 * Events leave only through the outbox of the command's open transaction (ARC-05, INV-REQ-1211): the writer appends
 * one row per event, under the emission guard, and nothing is published anywhere else. A rolled-back command leaves no
 * row. Despatch to the event log is the relay's, not this class's (TPL-17).
 */
class OutboxPackEventEmitter(
    private val writer: OutboxWriter,
    private val mapper: PackEventMapper = PackEventMapper(),
) : PackEventEmitter {
    override fun emit(
        events: List<PackEvent>,
        context: EventContext,
    ) {
        writer.append(events.map(mapper::map), context)
    }
}
