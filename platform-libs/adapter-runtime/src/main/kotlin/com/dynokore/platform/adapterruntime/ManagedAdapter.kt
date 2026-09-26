package com.dynokore.platform.adapterruntime

import java.time.Instant

/**
 * Lifecycle port every external interface adapter version implements (INV-REQ-1354).
 *
 * A [VersionedAdapterRouter][com.dynokore.platform.adapterruntime.cutover.VersionedAdapterRouter]
 * holds one [ManagedAdapter] per [AdapterVersion] and drives this lifecycle directly;
 * an adapter implementation never starts or stops itself.
 */
interface ManagedAdapter {
    /** This instance's own contract version. */
    val version: AdapterVersion

    /** The current lifecycle state ([AdapterState]). */
    val state: AdapterState

    /** Begins accepting work. Called once, before this instance is ever routed to. */
    fun start()

    /**
     * Stops accepting new work immediately, then blocks until every unit of work this
     * instance already accepted has finished, or until [deadline] passes, whichever is
     * first (INV-REQ-1354: "in-flight work finishes on the old one"). Idempotent: a
     * second call while already draining or stopped only waits for the same condition.
     */
    fun drainAndStop(deadline: Instant)
}
