package com.dynokore.gst.payload.domain

/** One failed Schematron assert, as plain domain data (INV-REQ-472). */
data class FailedAssertion(
    val id: String?,
    val location: String,
    val test: String,
    val role: String?,
    val text: String,
)

/** The result of one Schematron validation: the failed asserts in document order; empty when the document passes. */
data class ValidationOutcome(
    val failedAsserts: List<FailedAssertion>,
) {
    val passed: Boolean get() = failedAsserts.isEmpty()
}

/** Port: validates a document against the compiled rule set of one pack version (INV-REQ-472, INV-REQ-1359). */
interface SchematronValidator {
    fun validate(
        packVersion: String,
        document: ByteArray,
    ): ValidationOutcome
}

/** Port: the staged stylesheet bytes of a pack version, or null when the version is not staged (INV-REQ-1210). */
interface StagedPackSource {
    fun fetch(packVersion: String): ByteArray?
}

/** The staged-pack source holds no stylesheet for [packVersion]. */
class StagedPackMissingException(
    val packVersion: String,
) : RuntimeException("no staged Schematron pack for version $packVersion")
