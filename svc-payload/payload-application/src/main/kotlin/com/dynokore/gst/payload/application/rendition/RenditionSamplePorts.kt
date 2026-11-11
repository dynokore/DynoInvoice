package com.dynokore.gst.payload.application.rendition

import com.dynokore.gst.payload.domain.SampleCheckResult
import com.dynokore.gst.payload.domain.SampleResult
import com.dynokore.gst.payload.domain.SubmissionType
import com.dynokore.gst.payload.domain.WordingRequirement

/** The wording the Scenario pack lists for a pair; null for an unknown entity or a pair the pack does not list. */
fun interface RenditionWordingSource {
    fun requirementsFor(
        entityRef: String,
        scenario: String,
        submissionType: SubmissionType,
    ): List<WordingRequirement>?
}

/** Extracts the text of one PDF sample; null when no text can be found. */
fun interface SampleTextExtractor {
    fun extract(pdf: ByteArray): String?
}

/** Sample and standing store (INV-REQ-1033). */
interface RenditionSampleStore {
    fun findRecorded(
        entityRef: String,
        sampleSha256: String,
        scenario: String,
        submissionType: SubmissionType,
    ): SampleCheckResult?

    fun latest(entityRef: String): Map<Pair<String, SubmissionType>, SampleResult>

    @Suppress("LongParameterList")
    fun save(
        entityRef: String,
        sampleSha256: String,
        scenario: String,
        submissionType: SubmissionType,
        result: SampleCheckResult,
        latest: Map<Pair<String, SubmissionType>, SampleResult>,
    )
}

/** The one transaction boundary of a recording (INV-ADR-283 transactional outbox). */
interface RenditionUnitOfWork {
    fun <T> run(block: () -> T): T
}
