package com.dynokore.gst.reconciliation.application.workpaper

import com.dynokore.gst.reconciliation.domain.workpaper.WorkPaperSeal

/** The latest seal of a run: its reference, hash and seal version. */
data class SealedRef(
    val reference: String,
    val sha256Hex: String,
    val sealVersion: Int,
)

/** Source of the canonical JSON bytes of a run's work-paper; null when the run has none. */
fun interface WorkPaperSource {
    fun canonicalBytes(
        tenant: String,
        runId: String,
    ): ByteArray?
}

interface SealedWorkPapers {
    fun latest(
        tenant: String,
        runId: String,
    ): SealedRef?

    /** Stores the bytes as the given seal version and returns the reference. */
    fun put(
        tenant: String,
        runId: String,
        sealVersion: Int,
        bytes: ByteArray,
    ): String
}

fun interface ArtefactRecordedPublisher {
    fun publish(
        tenant: String,
        entityRef: String,
        artefactType: String,
        reference: String,
        sha256Hex: String,
    )
}

enum class SealOutcome { SEALED, ALREADY_SEALED, NO_WORK_PAPER }

class SealWorkPaper(
    private val source: WorkPaperSource,
    private val store: SealedWorkPapers,
    private val publisher: ArtefactRecordedPublisher,
) {
    fun execute(
        tenant: String,
        runId: String,
    ): SealOutcome {
        val canonical = source.canonicalBytes(tenant, runId) ?: return SealOutcome.NO_WORK_PAPER
        val seal = WorkPaperSeal.of(canonical)
        val latest = store.latest(tenant, runId)
        val outcome =
            if (latest != null && latest.sha256Hex == seal.sha256Hex) {
                SealOutcome.ALREADY_SEALED
            } else {
                val reference = store.put(tenant, runId, (latest?.sealVersion ?: 0) + 1, seal.bytes)
                publisher.publish(tenant, runId, ARTEFACT_TYPE, reference, seal.sha256Hex)
                SealOutcome.SEALED
            }
        return outcome
    }

    companion object {
        const val ARTEFACT_TYPE = "RECONCILIATION_WORK_PAPER"
    }
}
