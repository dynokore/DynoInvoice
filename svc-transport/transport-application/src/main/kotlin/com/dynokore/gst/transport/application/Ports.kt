package com.dynokore.gst.transport.application

import com.dynokore.gst.transport.domain.AdapterId
import com.dynokore.gst.transport.domain.AttemptCounts
import com.dynokore.gst.transport.domain.DocRef
import com.dynokore.gst.transport.domain.EntityRef
import com.dynokore.gst.transport.domain.ThroughputCeiling
import com.dynokore.gst.transport.domain.TransportOutcome
import java.time.Instant
import java.util.UUID

/**
 * The Access Point adapter port (INV-REQ-539): the one place an AP is spoken to. Credentials, the AP envelope, batch
 * packaging, AP error codes and transport-level retry live behind it; what comes out is a [TransportOutcome] and
 * nothing else. An implementation never throws for an AP answer: every failure it cannot classify is a transient
 * failure.
 */
interface AccessPointPort {
    val adapterId: AdapterId

    fun submit(
        doc: DocRef,
        payload: String,
    ): TransportOutcome
}

/** The adapters registered in this installation, by id (two may run at once, INV-REQ-796). */
interface AccessPointAdapters {
    fun find(id: AdapterId): AccessPointPort?

    fun ids(): Set<AdapterId>
}

/** The attempt log (INV-REQ-538): one attempt row per call of the port, with exactly one egress record. */
interface AttemptLog {
    /** The adapter an earlier attempt of this Doc UUID and version used: versions in flight finish on it. */
    fun adapterInFlight(doc: DocRef): AdapterId?

    /** The recorded outcome of a settled (accepted or rejected) earlier attempt: a repeat returns it. */
    fun settledOutcome(doc: DocRef): TransportOutcome?

    /** Records the attempt and its single egress before the AP is called; returns the attempt id. */
    fun begin(
        entity: EntityRef,
        doc: DocRef,
        adapter: AdapterId,
        at: Instant,
    ): UUID

    fun complete(
        attempt: UUID,
        outcome: TransportOutcome,
        at: Instant,
    )

    /** Attempts and failed attempts of [adapter] started at or after [since], counted across tenants. */
    fun counts(
        adapter: AdapterId,
        since: Instant,
    ): AttemptCounts
}

/** Which adapter an entity submits through. */
interface EntityAdapterAssignments {
    fun adapterFor(entity: EntityRef): AdapterId?

    fun assign(
        entity: EntityRef,
        adapter: AdapterId,
    )
}

/** The AP connection record's rate fields (INV-REQ-1317). */
interface ApConnections {
    fun ceiling(adapter: AdapterId): ThroughputCeiling?

    fun saveCeiling(
        adapter: AdapterId,
        ceiling: ThroughputCeiling,
    )
}
