package com.dynokore.gst.reconciliation.domain.annotation

import com.dynokore.gst.reconciliation.domain.variance.VarianceClass

/** A person's annotation of the unexplained residual of one Box in one run (INV-REQ-633 b4); the run is not changed. */
data class Annotation(
    val box: String,
    val varianceClass: VarianceClass?,
    val references: List<String>,
)

/** The outcome of an annotation request. */
sealed interface AnnotationDecision {
    data class Accepted(
        val annotation: Annotation,
    ) : AnnotationDecision

    data class Repeat(
        val existing: Annotation,
    ) : AnnotationDecision

    data class Refused(
        val reason: AnnotationRefusal,
    ) : AnnotationDecision
}

/**
 * Why an annotation is refused. Only CLASS_NOT_ASSIGNABLE has a catalogue code;
 * the status of the others is the endpoint slice's.
 */
enum class AnnotationRefusal(
    val code: String?,
) {
    /** A class outside the five a person may assign (INV-Q-7237 A). */
    CLASS_NOT_ASSIGNABLE("PER-015"),

    /** The contract precondition "The reconciliation run exists and is not yet attested." */
    RUN_ATTESTED(null),

    /** The request schema requires `references` with minItems 1. */
    NO_REFERENCES(null),
}
