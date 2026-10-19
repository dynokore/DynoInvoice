package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext

/**
 * [EventLogPresence] over `eventlog.events_missing` (owner ruling INV-Q-4350 (A)) on the event-log [eventLog] scope, a
 * jOOQ routine call in a transaction under the platform binding (coordinator ruling INV-Q-5614): the function, owned by
 * `eventlog_sys`, answers only for a topic that the runtime login produces and returns only the ids not present and the
 * topic's retention cut-off.
 */
class PostgresEventLogPresence(
    private val eventLog: TenantScope,
    private val dsl: DSLContext,
) : EventLogPresence {
    override fun missing(
        topic: String,
        eventIds: Collection<String>,
    ): MissingEvents {
        require(eventIds.size <= EventLogPresence.MAX_IDS) {
            "at most ${EventLogPresence.MAX_IDS} ids per call, was ${eventIds.size}"
        }
        val function = EventsMissing().call(topic, eventIds.toTypedArray())
        return eventLog.bindWorkload(TenantId.PLATFORM) {
            eventLog.transactions.inTransaction {
                val row = dsl.select(function.missing, function.cutoff).from(function).fetchOne()
                checkNotNull(row) { "events_missing returned no row" }
                val cutoff = checkNotNull(row.get(function.cutoff)) { "events_missing returned no cut-off for $topic" }
                MissingEvents(row.get(function.missing)?.toSet().orEmpty(), cutoff.toInstant())
            }
        }
    }
}
