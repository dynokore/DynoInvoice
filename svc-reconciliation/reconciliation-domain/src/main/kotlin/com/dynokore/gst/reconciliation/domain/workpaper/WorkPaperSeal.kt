package com.dynokore.gst.reconciliation.domain.workpaper

import java.security.MessageDigest
import java.util.HexFormat

/** The sealed form of a work-paper: its canonical JSON bytes and the lowercase SHA-256 hex of exactly those bytes. */
class WorkPaperSeal private constructor(
    bytes: ByteArray,
    val sha256Hex: String,
) {
    private val content: ByteArray = bytes.copyOf()

    val bytes: ByteArray get() = content.copyOf()

    override fun equals(other: Any?): Boolean =
        other is WorkPaperSeal && sha256Hex == other.sha256Hex && content.contentEquals(other.content)

    override fun hashCode(): Int = sha256Hex.hashCode()

    override fun toString(): String = "WorkPaperSeal(sha256Hex=$sha256Hex)"

    companion object {
        fun of(canonicalJson: ByteArray): WorkPaperSeal =
            WorkPaperSeal(
                canonicalJson,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalJson)),
            )
    }
}

/** The immutable object-store key of a sealed work-paper. */
object WorkPaperReference {
    fun of(
        tenantId: String,
        runId: String,
        sealVersion: Int,
    ): String {
        require(tenantId.isNotBlank()) { "tenantId must not be blank" }
        require(runId.isNotBlank()) { "runId must not be blank" }
        require(sealVersion >= 1) { "sealVersion must be at least 1" }
        return "reconciliation/$tenantId/$runId/v$sealVersion"
    }
}
