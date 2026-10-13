package com.dynokore.platform.rulepackkernel

/** The result of [PackContentValidator.validate]. */
sealed interface ContentValidationOutcome {
    /** The content is declarative data: no field or value looks like a script, class or executable payload. */
    data object Accepted : ContentValidationOutcome

    /** Refused — [reason] names the field or pattern that reads as executable content (INV-AC-2394, INV-REQ-2393). */
    data class Rejected(
        val reason: String,
    ) : ContentValidationOutcome
}
