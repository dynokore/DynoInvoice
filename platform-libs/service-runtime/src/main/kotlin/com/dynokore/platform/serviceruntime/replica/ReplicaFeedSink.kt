package com.dynokore.platform.serviceruntime.replica

/**
 * What a feed (the service's event consumer, or a fake in a test) calls on a replica.
 * The feed delivers per-source in order and supplies each event's monotonic version.
 */
interface ReplicaFeedSink<E> {
    fun accept(event: E)

    /**
     * A heartbeat: the source is at [version] now. Called often enough to hold a bounded
     * replica's lag below its bound.
     */
    fun observeHead(version: Long)

    fun onDisconnected()
}
