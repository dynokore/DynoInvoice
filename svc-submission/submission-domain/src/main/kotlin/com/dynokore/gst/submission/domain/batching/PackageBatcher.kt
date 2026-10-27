package com.dynokore.gst.submission.domain.batching

/** A ready document offered to the batcher, in queue order (INV-REQ-426). */
data class BatchCandidate(
    val docUuid: String,
    val version: Int,
    val documentTypeCode: String,
    val sizeBytes: Long,
)

/** One package: one document type, members in input order (INV-REQ-424). */
data class DocumentPackage(
    val documentTypeCode: String,
    val members: List<BatchCandidate>,
)

/** A candidate above the byte limit: never packed, never truncated (INV-REQ-426); the caller maps it to a hold. */
data class Oversized(
    val docUuid: String,
    val version: Int,
    val sizeBytes: Long,
)

data class BatchResult(
    val packages: List<DocumentPackage>,
    val refused: List<Oversized>,
)

/** Limits of a package; the values are set where the service is wired (INV-REQ-1361). */
data class BatchLimits(
    val maxDocuments: Int,
    val maxBytes: Long,
) {
    init {
        require(maxDocuments > 0) { "maxDocuments must be positive" }
        require(maxBytes > 0) { "maxBytes must be positive" }
    }
}

/**
 * Splits by document type first, then by Doc UUID uniqueness and count, then by size (INV-REQ-426, INV-REQ-552).
 * Pure and stateless: equal input gives equal output, so calls may run in parallel (INV-REQ-1361).
 */
class PackageBatcher(
    private val limits: BatchLimits,
) {
    fun batch(candidates: List<BatchCandidate>): BatchResult {
        val refused = mutableListOf<Oversized>()
        val packages = mutableListOf<DocumentPackage>()
        val groups = LinkedHashMap<String, MutableList<BatchCandidate>>()
        for (candidate in candidates) {
            if (candidate.sizeBytes > limits.maxBytes) {
                refused += Oversized(candidate.docUuid, candidate.version, candidate.sizeBytes)
            } else {
                groups.getOrPut(candidate.documentTypeCode) { mutableListOf() } += candidate
            }
        }
        for ((code, group) in groups) {
            val members = mutableListOf<BatchCandidate>()
            var bytes = 0L
            for (candidate in group) {
                val closes =
                    members.isNotEmpty() &&
                        (
                            members.size + 1 > limits.maxDocuments ||
                                bytes + candidate.sizeBytes > limits.maxBytes ||
                                members.any { it.docUuid == candidate.docUuid }
                        )
                if (closes) {
                    packages += DocumentPackage(code, members.toList())
                    members.clear()
                    bytes = 0L
                }
                members += candidate
                bytes += candidate.sizeBytes
            }
            if (members.isNotEmpty()) packages += DocumentPackage(code, members.toList())
        }
        return BatchResult(packages, refused)
    }

    /** One package of one member for each member, in member order (INV-REQ-553). */
    fun resplit(pack: DocumentPackage): List<DocumentPackage> =
        pack.members.map { DocumentPackage(pack.documentTypeCode, listOf(it)) }
}
