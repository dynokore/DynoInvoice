package com.dynokore.gst.payload.domain

import java.text.Normalizer

/** One wording the scenario pack requires on a rendition, with its citation (INV-REQ-1032). */
data class WordingRequirement(
    val text: String,
    val citation: String,
)

/** The outcome of one rendition sample check. */
enum class SampleResult {
    PASSED,
    FAILED,
}

/** The check outcome; `missingWording` holds the original `text` of each absent requirement, in input order. */
data class SampleCheckResult(
    val result: SampleResult,
    val missingWording: List<String>,
    val textFound: Boolean,
)

/**
 * The rendition sample check (INV-REQ-1033, INV-Q-6321 option A): a normalised, case-sensitive substring comparison.
 * The words "Tax Invoice" are not required for Type 1A and 1B (INV-REQ-647). Pure: no I/O, no document argument.
 */
object RenditionWordingChecker {
    private const val TAX_INVOICE = "Tax Invoice"
    private val whitespace = Regex("[\\s\\p{Z}]+")

    fun check(
        requirements: List<WordingRequirement>,
        submissionType: SubmissionType,
        extractedText: String?,
    ): SampleCheckResult {
        val exempt = submissionType == SubmissionType.TYPE_1A || submissionType == SubmissionType.TYPE_1B
        val remaining = requirements.filterNot { exempt && normalise(it.text) == TAX_INVOICE }
        val text = extractedText?.let(::normalise).orEmpty()
        val textFound = text.isNotEmpty()
        val missing = remaining.filterNot { textFound && normalise(it.text) in text }.map { it.text }
        return SampleCheckResult(
            result = if (missing.isEmpty()) SampleResult.PASSED else SampleResult.FAILED,
            missingWording = missing,
            textFound = textFound,
        )
    }

    private fun normalise(value: String): String =
        whitespace.replace(Normalizer.normalize(value, Normalizer.Form.NFC), " ").trim()
}
