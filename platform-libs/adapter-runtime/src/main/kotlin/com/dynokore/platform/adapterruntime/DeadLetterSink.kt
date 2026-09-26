package com.dynokore.platform.adapterruntime

/**
 * Where a [ResilientExecutor] sends a [DeadLetterEntry] once its bounded retry ends
 * without success (INV-REQ-1353). This module ships only
 * [InMemoryDeadLetterSink][com.dynokore.platform.adapterruntime.deadletter.InMemoryDeadLetterSink];
 * a service durably persisting dead letters (e.g. to its own outbox table) implements
 * this port itself.
 */
fun interface DeadLetterSink {
    fun send(entry: DeadLetterEntry)
}
