package com.dynokore.gst.reconciliation.domain.annotation

import com.dynokore.gst.reconciliation.domain.annotation.AnnotationDecision.Accepted
import com.dynokore.gst.reconciliation.domain.annotation.AnnotationDecision.Refused
import com.dynokore.gst.reconciliation.domain.annotation.AnnotationDecision.Repeat
import com.dynokore.gst.reconciliation.domain.variance.VarianceClasses

/** Decision of `annotateVariance` (INV-Q-7237 A, INV-REQ-633 b4). Pure: no I/O, no clock; the run is never an input. */
object VarianceAnnotations {
    fun decide(
        runAttested: Boolean,
        existing: Annotation?,
        box: String,
        requestedClass: String?,
        references: List<String>,
    ): AnnotationDecision {
        val parsed = VarianceClasses.parseAssignable(requestedClass)
        val requested = Annotation(box, parsed, references)
        return when {
            runAttested -> Refused(AnnotationRefusal.RUN_ATTESTED)
            references.isEmpty() -> Refused(AnnotationRefusal.NO_REFERENCES)
            requestedClass != null && parsed == null -> Refused(AnnotationRefusal.CLASS_NOT_ASSIGNABLE)
            existing != null && existing.box == box && existing == requested -> Repeat(existing)
            else -> Accepted(requested)
        }
    }
}
