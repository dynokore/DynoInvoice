package com.dynokore.gst.reconciliation.application.workpaper

import com.dynokore.gst.reconciliation.domain.annotation.Annotation
import java.time.Instant

/** One append-only annotation record; [annotatedBy] is the acting user ID, never a role (INV-Q-10647). */
data class StoredAnnotation(
    val runId: String,
    val annotation: Annotation,
    val annotatedBy: String,
    val annotatedAt: Instant,
)

data class StoredAttestation(
    val attestedBy: String,
    val attestedAt: Instant,
)

/** Append-only annotation history of the runs. Every method reads or writes one tenant only. */
interface RunAnnotations {
    /** The newest revision for the Box, or null. */
    fun latest(
        tenant: String,
        runId: String,
        box: String,
    ): StoredAnnotation?

    /** The newest revision of each Box of the run. */
    fun all(
        tenant: String,
        runId: String,
    ): List<StoredAnnotation>

    /** False when a concurrent write took the revision; nothing is stored then. */
    fun insert(
        tenant: String,
        stored: StoredAnnotation,
    ): Boolean
}

fun interface RunAttestations {
    fun get(
        tenant: String,
        runId: String,
    ): StoredAttestation?
}
