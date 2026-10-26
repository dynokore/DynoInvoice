package com.dynokore.gst.ingestion.application.recording

import com.dynokore.gst.ingestion.domain.recording.CanonicalAnswer
import com.dynokore.gst.ingestion.domain.recording.RecordingMapping
import com.dynokore.gst.ingestion.domain.recording.RecordingOutcome
import java.util.UUID

/** The request ingestion-svc hands to canonical-svc; [body] is the `RecordDocumentRequest` JSON, passed unchanged. */
data class RecordDocumentCommand(
    val tenantId: UUID,
    val sourceSystem: String,
    val sourceKey: String,
    val sourceRevision: Long,
    val body: String,
)

/** The `recordDocument` operation of the canonical contract (sync edge `ingestion-svc->canonical-svc`). */
fun interface CanonicalRecorder {
    fun record(command: RecordDocumentCommand): CanonicalAnswer
}

/**
 * Records a transaction through canonical-svc and maps the answer (INV-REQ-806). No pair is stored here: the decision
 * and the unique constraint are canonical-svc's (INV-REQ-1191).
 */
class RecordTransaction(
    private val canonical: CanonicalRecorder,
) {
    fun execute(command: RecordDocumentCommand): RecordingOutcome = RecordingMapping.map(canonical.record(command))
}
