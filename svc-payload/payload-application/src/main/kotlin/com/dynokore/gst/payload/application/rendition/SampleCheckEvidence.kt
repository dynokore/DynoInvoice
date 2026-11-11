package com.dynokore.gst.payload.application.rendition

import com.dynokore.gst.payload.domain.SampleResult
import com.dynokore.gst.payload.domain.SubmissionType
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.serviceruntime.events.EvidenceArtefact
import com.dynokore.platform.serviceruntime.events.EvidenceArtefactRecorder
import com.dynokore.platform.serviceruntime.events.EvidenceArtefactType
import java.time.Instant

/**
 * The `SAMPLE_CHECK` evidence artefact of one rendition sample check (INV-REQ-1036 behavior 2). The reference is
 * `entityRef/checkId`, unique per check, so a later check never reuses an earlier reference; the hash is the
 * SHA-256 of the canonical bytes (UTF-8 JSON, keys in alphabetical order, no whitespace) of every field.
 * `checkedBy` is a user ID, never a role (INV-Q-10647).
 */
object SampleCheckArtefact {
    private val SHA256 = Regex("^[0-9a-f]{64}$")

    @Suppress("LongParameterList")
    fun of(
        entityRef: String,
        checkId: String,
        scenario: String,
        submissionType: SubmissionType,
        result: SampleResult,
        missingWording: List<String>,
        textFound: Boolean,
        sampleSha256: String,
        checkedAt: Instant,
        checkedBy: String,
    ): EvidenceArtefact {
        require(entityRef.isNotBlank()) { "entityRef must not be blank" }
        require(checkId.isNotBlank()) { "checkId must not be blank" }
        require(checkedBy.isNotBlank()) { "checkedBy must not be blank" }
        require(SHA256.matches(sampleSha256)) { "sampleSha256 is a SHA-256 as 64 lowercase hex characters" }
        val bytes =
            canonicalBytes(
                entityRef,
                checkId,
                scenario,
                submissionType,
                result,
                missingWording,
                textFound,
                sampleSha256,
                checkedAt,
                checkedBy,
            )
        return EvidenceArtefact.of(EvidenceArtefactType.SAMPLE_CHECK, "$entityRef/$checkId", bytes)
    }

    /** Keys in alphabetical order, no whitespace; exposed so a caller can retain the exact hashed bytes. */
    @Suppress("LongParameterList")
    fun canonicalBytes(
        entityRef: String,
        checkId: String,
        scenario: String,
        submissionType: SubmissionType,
        result: SampleResult,
        missingWording: List<String>,
        textFound: Boolean,
        sampleSha256: String,
        checkedAt: Instant,
        checkedBy: String,
    ): ByteArray =
        buildString {
            append('{')
            append("\"checkId\":").append(json(checkId))
            append(",\"checkedAt\":").append(json(checkedAt.toString()))
            append(",\"checkedBy\":").append(json(checkedBy))
            append(",\"entityRef\":").append(json(entityRef))
            append(",\"missingWording\":[").append(missingWording.joinToString(",") { json(it) }).append(']')
            append(",\"result\":").append(json(result.name))
            append(",\"sampleSha256\":").append(json(sampleSha256))
            append(",\"scenario\":").append(json(scenario))
            append(",\"submissionType\":").append(json(submissionType.name))
            append(",\"textFound\":").append(textFound)
            append('}')
        }.toByteArray(Charsets.UTF_8)

    private fun json(s: String): String =
        buildString {
            append('"')
            for (c in s) {
                when {
                    c == '"' -> append("\\\"")
                    c == '\\' -> append("\\\\")
                    c < ' ' -> append("\\u%04x".format(java.util.Locale.ROOT, c.code))
                    else -> append(c)
                }
            }
            append('"')
        }
}

/** Publishes one sample check as `EvidenceArtefactRecorded` through the port, once per call (INV-ADR-283). */
class RecordSampleCheckEvidence(
    private val recorder: EvidenceArtefactRecorder,
) {
    @Suppress("LongParameterList")
    fun record(
        entityRef: String,
        checkId: String,
        scenario: String,
        submissionType: SubmissionType,
        result: SampleResult,
        missingWording: List<String>,
        textFound: Boolean,
        sampleSha256: String,
        checkedAt: Instant,
        checkedBy: String,
        context: EventContext,
    ) {
        val artefact =
            SampleCheckArtefact.of(
                entityRef,
                checkId,
                scenario,
                submissionType,
                result,
                missingWording,
                textFound,
                sampleSha256,
                checkedAt,
                checkedBy,
            )
        recorder.record(artefact, context)
    }
}
