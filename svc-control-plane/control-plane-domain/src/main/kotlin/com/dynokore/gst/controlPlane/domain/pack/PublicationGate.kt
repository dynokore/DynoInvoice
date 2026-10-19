package com.dynokore.gst.controlPlane.domain.pack

/** SHA-256 as 64 lower-case hex characters (the `packContentHash` of the feed index schema). */
@JvmInline
value class ContentHash(
    val hex: String,
) {
    init {
        require(hex.matches(PATTERN)) { "a content hash is 64 lower-case hex characters" }
    }

    override fun toString(): String = hex

    private companion object {
        val PATTERN = Regex("[0-9a-f]{64}")
    }
}

enum class StagingResult { PASSED, FAILED, PENDING }

/** One staging evidence record (INV-Q-4878): the result for a pack version and the hash of the content it ran over. */
data class StagingEvidence(
    val packRef: PackRef,
    val result: StagingResult,
    val packContentHash: ContentHash,
)

/** Why a publication is refused. The refusal names the record that is missing or does not match. */
sealed interface PublicationRefusal {
    val packRef: PackRef

    /** The version is not signed (a draft), so publishPack is refused (INV-AC-2591). */
    data class NotSigned(
        override val packRef: PackRef,
    ) : PublicationRefusal

    /** No minimum engine version is declared (INV-REQ-1094, INV-AC-2078). */
    data class MinEngineVersionMissing(
        override val packRef: PackRef,
    ) : PublicationRefusal

    /** No staging evidence record names this pack version. */
    data class NoStagingRecord(
        override val packRef: PackRef,
    ) : PublicationRefusal

    /** Records name the version, but none has the result PASSED. */
    data class StagingNotPassed(
        override val packRef: PackRef,
        val results: List<StagingResult>,
    ) : PublicationRefusal

    /** A PASSED record names the version, but its hash is not the hash of the content at this moment. */
    data class StagingHashMismatch(
        override val packRef: PackRef,
        val recordedHash: ContentHash,
        val currentHash: ContentHash,
    ) : PublicationRefusal
}

/**
 * The publication precondition (coordinator ruling INV-Q-4878): fails closed, no exception, no waiver. A version is
 * published only when it is signed, declares a minimum engine version, and a staging record with result PASSED names it
 * with a `packContentHash` equal to [currentHash], the SHA-256 computed at this moment over the exact content.
 */
object PublicationGate {
    fun check(
        pack: RegulatoryPack,
        records: List<StagingEvidence>,
        currentHash: ContentHash,
    ): PublicationRefusal? {
        val own = records.filter { it.packRef == pack.ref }
        val passed = own.filter { it.result == StagingResult.PASSED }
        return when {
            pack.status != PackStatus.SIGNED -> {
                PublicationRefusal.NotSigned(pack.ref)
            }

            pack.minEngineVersion == null -> {
                PublicationRefusal.MinEngineVersionMissing(pack.ref)
            }

            own.isEmpty() -> {
                PublicationRefusal.NoStagingRecord(pack.ref)
            }

            passed.isEmpty() -> {
                PublicationRefusal.StagingNotPassed(pack.ref, own.map { it.result })
            }

            passed.none { it.packContentHash == currentHash } -> {
                PublicationRefusal.StagingHashMismatch(pack.ref, passed.last().packContentHash, currentHash)
            }

            else -> {
                null
            }
        }
    }
}
