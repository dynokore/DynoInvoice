package com.dynokore.gst.submission.application.correction

/** Why a net finding was recorded (INV-REQ-558 behavior 6, owner option A of 2026-11-20). */
enum class NetFindingKind { AMENDED_AFTER_TRANSMISSION, NET_NOT_POSITIVE }

/**
 * A finding about a credit note and its net. The key is (kind, creditNoteDocUuid, creditNoteVersion).
 *
 * @property creditNoteVersion the refused version; null for [NetFindingKind.NET_NOT_POSITIVE].
 * @property originalDocUuid the original the credit note was netted into; null for [NetFindingKind.NET_NOT_POSITIVE].
 */
data class NetFinding(
    val kind: NetFindingKind,
    val creditNoteDocUuid: String,
    val creditNoteVersion: Int?,
    val originalDocUuid: String?,
)

/** Reads the register entry of a credit note. */
fun interface CreditNoteVersions {
    /** The source version of the register entry of the credit note; null when there is none. */
    fun registeredVersionOf(creditNoteDocUuid: String): Int?
}

/** Records net findings. */
fun interface NetFindings {
    /** True when the finding is new, false when one with the same key (kind, credit note, version) exists. */
    fun record(finding: NetFinding): Boolean
}
