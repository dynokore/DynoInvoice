package com.dynokore.platform.evidencestore.exportpackage

/**
 * A read-only view of the files of an exported BagIt package (RFC 8493), whatever holds them: a directory, a zip or
 * an object-store prefix are adapters of a later slice (INV-REQ-1347).
 */
interface PackageSource {
    /** Relative paths, "/" separated, files only. */
    fun paths(): Set<String>

    /** The bytes of one of [paths]; any exception is reported by the verifier as a problem, never as a pass. */
    fun read(path: String): ByteArray
}

enum class PackageProblemCode {
    MISSING_DECLARATION,
    BAD_DECLARATION,
    MISSING_PAYLOAD_MANIFEST,
    MALFORMED_MANIFEST_LINE,
    MANIFEST_PATH_NOT_PAYLOAD,
    DUPLICATE_MANIFEST_ENTRY,
    LISTED_FILE_MISSING,
    PAYLOAD_FILE_NOT_LISTED,
    DIGEST_MISMATCH,
    MISSING_TAG_MANIFEST,
    TAG_FILE_NOT_LISTED,
    TAG_DIGEST_MISMATCH,
    MISSING_BAG_INFO_FIELD,
    OXUM_MISMATCH,
    CLASS_COUNT_MISMATCH,
    UNKNOWN_CONTENT_DIRECTORY,
    SOURCE_READ_FAILED,
}

data class PackageProblem(
    val code: PackageProblemCode,
    val path: String?,
)

sealed interface PackageVerification {
    data class Valid(
        val payloadFiles: Int,
        val payloadOctets: Long,
        val itemCounts: Map<String, Int>,
    ) : PackageVerification

    data class Invalid(
        val problems: List<PackageProblem>,
    ) : PackageVerification
}
