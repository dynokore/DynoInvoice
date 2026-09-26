package com.dynokore.platform.adapterruntime.deadletter

import com.dynokore.platform.adapterruntime.DeadLetterEntry
import com.dynokore.platform.adapterruntime.DeadLetterSink
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The one [DeadLetterSink] this module ships: every entry kept in process memory
 * only, in arrival order. Adequate for a test double or for a caller that polls
 * [entries] itself and persists them; a service that needs dead letters to survive a
 * restart implements [DeadLetterSink] against its own outbox instead.
 */
class InMemoryDeadLetterSink : DeadLetterSink {
    private val received = CopyOnWriteArrayList<DeadLetterEntry>()

    override fun send(entry: DeadLetterEntry) {
        received.add(entry)
    }

    /** Every entry received so far, oldest first. */
    val entries: List<DeadLetterEntry> get() = received.toList()
}
