package com.dynokore.gst.controlPlane.application.pack

import com.dynokore.gst.controlPlane.domain.pack.ContentHash
import com.dynokore.gst.controlPlane.domain.pack.PackRef
import com.dynokore.gst.controlPlane.domain.pack.RegulatoryPack
import com.dynokore.gst.controlPlane.domain.pack.StagingEvidence

/** The operator roles of INV-NOTE-3 §22.2. */
object OperatorRole {
    const val AUTHOR = "regulatory.author"
    const val APPROVER = "regulatory.approver"
}

/** Whether an actor holds a platform operator role (CS-23: re-checked here, never taken from the request). */
fun interface OperatorRoles {
    fun holds(
        actor: String,
        role: String,
    ): Boolean
}

/** Pack versions and their authors, in the open transaction of the command. */
interface PackRepository {
    fun find(ref: PackRef): RegulatoryPack?

    /** Inserts a new version or stores the changes of an existing one (authors, signature, publication). */
    fun save(pack: RegulatoryPack)
}

/** Staging evidence records of a pack version; read only (rows are written by the stage of INV-T-4985 alone). */
fun interface StagingEvidenceReader {
    fun forPack(ref: PackRef): List<StagingEvidence>
}

/** The SHA-256 over the exact content of the pack version, computed at the moment of the call (INV-Q-4878). */
fun interface PackContentHasher {
    fun hash(pack: RegulatoryPack): ContentHash
}

/**
 * Releases a signed version to the feed and as a bundle, in the command transaction (INV-REQ-1077, INV-REQ-1079).
 * The bundle, the feed index and the release history are written by the adapter.
 */
fun interface PackReleaser {
    fun release(pack: RegulatoryPack)
}

/** Correlation of a command, from the request (API-40). */
data class RequestContext(
    val correlationId: String,
    val causationId: String,
)

/**
 * A refusal an operation answers instead of performing: a stable [code] and the [detail] that names the record at
 * fault. [encode] is what the operation runner carries as the unmet-precondition code; [parse] reads it back.
 */
data class Refusal(
    val code: String,
    val detail: String,
) {
    fun encode(): String = "$code $detail"

    companion object {
        const val PACK_NOT_FOUND = "PACK_NOT_FOUND"
        const val SEPARATION_OF_DUTY = "SEPARATION_OF_DUTY"
        const val SIGNING_KEY_MISSING = "SIGNING_KEY_MISSING"
        const val NOT_SIGNED = "NOT_SIGNED"
        const val MIN_ENGINE_VERSION_MISSING = "MIN_ENGINE_VERSION_MISSING"
        const val NO_STAGING_RECORD = "NO_STAGING_RECORD"
        const val STAGING_NOT_PASSED = "STAGING_NOT_PASSED"
        const val STAGING_HASH_MISMATCH = "STAGING_HASH_MISMATCH"

        fun parse(encoded: String): Refusal = Refusal(encoded.substringBefore(' '), encoded.substringAfter(' ', ""))
    }
}
