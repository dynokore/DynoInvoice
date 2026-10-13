package com.dynokore.platform.outboxandqueue

import javax.sql.DataSource

/**
 * [EventLogPresence] over `eventlog.events_missing` (owner ruling INV-Q-4350 (A)) on [eventLogSource], a connection of
 * the service's runtime login outside any tenant transaction: the function, owned by `eventlog_sys`, answers only for
 * a topic that login produces and returns only the ids not present and the topic's retention cut-off.
 */
class PostgresEventLogPresence(
    private val eventLogSource: DataSource,
) : EventLogPresence {
    override fun missing(
        topic: String,
        eventIds: Collection<String>,
    ): MissingEvents {
        require(eventIds.size <= EventLogPresence.MAX_IDS) {
            "at most ${EventLogPresence.MAX_IDS} ids per call, was ${eventIds.size}"
        }
        return eventLogSource.connection.use { connection ->
            val ids = connection.createArrayOf("text", eventIds.toTypedArray())
            try {
                connection
                    .prepareStatement(
                        "select missing, cutoff from ${EventLogSchema.SCHEMA}.${EventLogSchema.EVENTS_MISSING}(?, ?)",
                    ).use { statement ->
                        statement.setString(1, topic)
                        statement.setArray(2, ids)
                        statement.executeQuery().use { rows ->
                            check(rows.next()) { "events_missing returned no row" }
                            val cutoff =
                                checkNotNull(rows.getTimestamp(2)) { "events_missing returned no cut-off for $topic" }

                            @Suppress("UNCHECKED_CAST")
                            val missing = (rows.getArray(1)?.array as? Array<String>)?.toSet() ?: emptySet()
                            MissingEvents(missing, cutoff.toInstant())
                        }
                    }
            } finally {
                ids.free()
            }
        }
    }
}
