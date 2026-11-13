package com.dynokore.gst.consoleBff.application.exceptions

import com.dynokore.gst.consoleBff.domain.exceptions.DocumentHeldFacts
import com.dynokore.gst.consoleBff.domain.exceptions.DocumentReleasedFacts

/**
 * Reads the DocumentHeld payload (INV-AC-21027). A missing or mistyped required member is a `ContractViolation`.
 * `envelopeEntityRef` is the envelope entity when it names a legal entity, else null; it is used only when the payload
 * has no `entityRef` (CloudEvents envelope attribute as context, fail closed).
 */
interface DocumentHeldReader {
    fun read(
        dataJson: String,
        envelopeEntityRef: String?,
    ): DocumentHeldFacts
}

/** Reads the DocumentReleased payload; a missing or mistyped required member is a `ContractViolation`. */
interface DocumentReleasedReader {
    fun read(dataJson: String): DocumentReleasedFacts
}
