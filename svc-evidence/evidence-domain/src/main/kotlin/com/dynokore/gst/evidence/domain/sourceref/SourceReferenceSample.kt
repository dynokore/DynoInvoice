package com.dynokore.gst.evidence.domain.sourceref

import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

/** A reference to a source document in the tenant's document store (INV-REQ-646). */
data class SourceReference(
    val docUuid: UUID,
    val version: Int,
    val reference: String,
) {
    init {
        require(version >= 1) { "version must be at least 1" }
        require(reference.isNotBlank()) { "reference must not be blank" }
        require(reference.length <= MAX_REFERENCE_LENGTH) {
            "reference must be at most $MAX_REFERENCE_LENGTH characters"
        }
    }

    companion object {
        /** maxLength of sourceDocumentRef in the platform-contracts transaction-request schema. */
        const val MAX_REFERENCE_LENGTH = 512
    }
}

/** Number of references sampled per period; configuration, read by the adapter slice. */
data class SampleSize(
    val count: Int,
) {
    init {
        require(count >= 1) { "count must be at least 1" }
    }
}

/** Answer of one resolve check. NOT_VERIFIED means no answer, which is not a failure. */
enum class ResolveOutcome { RESOLVED, UNRESOLVED, NOT_VERIFIED }

/** Effect on readiness. There is deliberately no value that blocks a submission. */
enum class ReadinessEffect { NONE, ADVISORY_FINDING }

data class SampleReport(
    val periodKey: String,
    val checked: Int,
    val resolved: Int,
    val unresolved: List<SourceReference>,
    val notVerified: List<SourceReference>,
    val effect: ReadinessEffect,
)

object SourceReferenceSampler {
    /** Repeatable sample: the same period and references give the same list for any input order. */
    fun select(
        references: List<SourceReference>,
        periodKey: String,
        size: SampleSize,
    ): List<SourceReference> {
        require(periodKey.isNotBlank()) { "periodKey must not be blank" }
        val identities = HashSet<Pair<UUID, Int>>()
        references.forEach {
            require(identities.add(it.docUuid to it.version)) {
                "duplicate reference for ${it.docUuid} version ${it.version}"
            }
        }
        return references
            .sortedBy { orderingKey(periodKey, it) }
            .take(minOf(size.count, references.size))
    }

    fun report(
        periodKey: String,
        sample: List<SourceReference>,
        outcomes: Map<SourceReference, ResolveOutcome>,
    ): SampleReport {
        require(periodKey.isNotBlank()) { "periodKey must not be blank" }
        require(outcomes.keys == sample.toSet()) { "outcome keys must equal the sample entries" }
        val unresolved = sample.filter { outcomes[it] == ResolveOutcome.UNRESOLVED }
        return SampleReport(
            periodKey = periodKey,
            checked = sample.size,
            resolved = sample.count { outcomes[it] == ResolveOutcome.RESOLVED },
            unresolved = unresolved,
            notVerified = sample.filter { outcomes[it] == ResolveOutcome.NOT_VERIFIED },
            effect = if (unresolved.isEmpty()) ReadinessEffect.NONE else ReadinessEffect.ADVISORY_FINDING,
        )
    }

    private fun orderingKey(
        periodKey: String,
        ref: SourceReference,
    ): String {
        val input = periodKey + "\n" + ref.docUuid.toString() + "\n" + ref.version
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return HexFormat.of().formatHex(digest)
    }
}
