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

    /**
     * The feed has delivered a full sync or snapshot up to [version]. Until this is called after a
     * start, the replica's lag is unknown and a bounded replica uses its fallback.
     */
    fun onSnapshotComplete(version: Long)

    fun onDisconnected()
}
