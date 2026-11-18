package com.dynokore.gst.reconciliation.application.workpaper

import com.dynokore.gst.reconciliation.application.run.RunReader
import com.dynokore.gst.reconciliation.domain.annotation.AnnotationDecision
import com.dynokore.gst.reconciliation.domain.annotation.AnnotationRefusal
import com.dynokore.gst.reconciliation.domain.annotation.VarianceAnnotations
import java.time.Clock

sealed interface AnnotationResult {
    data class Accepted(
        val stored: StoredAnnotation,
    ) : AnnotationResult

    data class Replayed(
        val stored: StoredAnnotation,
    ) : AnnotationResult

    data class Refused(
        val reason: AnnotationRefusal,
    ) : AnnotationResult

    data object RunNotFound : AnnotationResult

    data object BoxNotFound : AnnotationResult
}

/** annotateVariance: the domain decides, this use case orders the I/O (INV-REQ-633 b4). The run is never changed. */
class AnnotateVariance(
    private val runs: RunReader,
    private val annotations: RunAnnotations,
    private val attestations: RunAttestations,
    private val clock: Clock,
) {
    @Suppress("LongParameterList")
    fun execute(
        tenant: String,
        runId: String,
        box: String,
        requestedClass: String?,
        references: List<String>,
        actorUserId: String,
    ): AnnotationResult {
        require(actorUserId.isNotBlank()) { "actorUserId must not be blank" }
        val snapshot = runs.find(tenant, runId)
        // A lost insert race re-reads and decides once more; a second loss is a conflict, never a silent overwrite.
        return when {
            snapshot == null -> {
                AnnotationResult.RunNotFound
            }

            snapshot.boxes.none { it.box == box } -> {
                AnnotationResult.BoxNotFound
            }

            else -> {
                attempt(tenant, runId, box, requestedClass, references, actorUserId)
                    ?: attempt(tenant, runId, box, requestedClass, references, actorUserId)
                    ?: error("annotation of box $box of run $runId lost the revision twice")
            }
        }
    }

    /** The result, or null when a concurrent write took the revision. */
    @Suppress("LongParameterList")
    private fun attempt(
        tenant: String,
        runId: String,
        box: String,
        requestedClass: String?,
        references: List<String>,
        actorUserId: String,
    ): AnnotationResult? =
        when (val decision = decide(tenant, runId, box, requestedClass, references)) {
            is AnnotationDecision.Refused -> {
                AnnotationResult.Refused(decision.reason)
            }

            is AnnotationDecision.Repeat -> {
                replay(tenant, runId, box)
            }

            is AnnotationDecision.Accepted -> {
                val stored = StoredAnnotation(runId, decision.annotation, actorUserId, clock.instant())
                if (annotations.insert(tenant, stored)) AnnotationResult.Accepted(stored) else null
            }
        }

    private fun decide(
        tenant: String,
        runId: String,
        box: String,
        requestedClass: String?,
        references: List<String>,
    ) = VarianceAnnotations.decide(
        runAttested = attestations.get(tenant, runId) != null,
        existing = annotations.latest(tenant, runId, box)?.annotation,
        box = box,
        requestedClass = requestedClass,
        references = references,
    )

    private fun replay(
        tenant: String,
        runId: String,
        box: String,
    ): AnnotationResult =
        AnnotationResult.Replayed(
            checkNotNull(annotations.latest(tenant, runId, box)) { "repeat without a stored annotation" },
        )
}
