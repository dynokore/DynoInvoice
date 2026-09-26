package com.dynokore.platform.adapterruntime.connection

import com.dynokore.platform.adapterruntime.AdapterVersion
import com.dynokore.platform.adapterruntime.ConnectionStatus
import com.dynokore.platform.adapterruntime.ConnectionStatusReporter
import java.util.concurrent.ConcurrentHashMap

/**
 * The one [ConnectionStatusReporter] this module ships: the latest report per
 * [AdapterVersion], held in process memory only. A service that needs this durable
 * or visible cross-instance (its own job-and-connection-status view, §35.13) reads
 * [current] on its own schedule and persists what it finds; this class does not do
 * that itself, the same division as
 * [InMemoryDeadLetterSink][com.dynokore.platform.adapterruntime.deadletter.InMemoryDeadLetterSink].
 */
class InMemoryConnectionStatusReporter : ConnectionStatusReporter {
    private val latest = ConcurrentHashMap<AdapterVersion, ConnectionStatus>()

    override fun report(status: ConnectionStatus) {
        latest[status.version] = status
    }

    override fun current(version: AdapterVersion): ConnectionStatus? = latest[version]
}
