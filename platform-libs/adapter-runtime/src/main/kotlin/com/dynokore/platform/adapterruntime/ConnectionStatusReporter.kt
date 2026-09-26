package com.dynokore.platform.adapterruntime

/**
 * Where an adapter reports its connection health, and where a job-and-connection
 * status view (owned by the service that embeds this module) reads it back from
 * (§35.13). An adapter calls [report] after every connection attempt and on a
 * periodic health check; it never maintains this state itself.
 */
interface ConnectionStatusReporter {
    /** Records [status] as the latest report for its [ConnectionStatus.version]. */
    fun report(status: ConnectionStatus)

    /** The latest report for [version], or `null` if none has ever been made. */
    fun current(version: AdapterVersion): ConnectionStatus?
}
