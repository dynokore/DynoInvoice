package com.dynokore.gst.reconciliation.application.workpaper

import com.dynokore.gst.reconciliation.domain.annotation.Annotation
import java.time.Instant
import java.time.LocalDate

/** One append-only annotation record; [annotatedBy] is the acting user ID, never a role (INV-Q-10647). */
data class StoredAnnotation(
    val runId: String,
    val annotation: Annotation,
    val annotatedBy: String,
    val annotatedAt: Instant,
)

/** The sole-operator acknowledgement of the single-attestation election (INV-REQ-994). */
data class SoleAttester(
    val acknowledgedBy: String,
    val acknowledgedOn: LocalDate,
)

data class StoredAttestation(
    val attestedBy: String,
    val attestedAt: Instant,
    val soleAttester: SoleAttester? = null,
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

/** The acting user ID that uploaded the return version of the run; null when it cannot be determined. */
fun interface RunPreparers {
    fun preparerOf(
        tenant: String,
        runId: String,
    ): String?
}

/** Non-null only when the single-attestation option of VP-40 is in force for the entity on that date. */
fun interface SingleAttestationPolicy {
    fun soleOperator(
        tenant: String,
        entityRef: String,
        on: LocalDate,
    ): SoleAttester?
}

/** False when a concurrent writer already stored an attestation for the run. */
fun interface RunAttestationWriter {
    fun insert(
        tenant: String,
        runId: String,
        stored: StoredAttestation,
    ): Boolean
}
