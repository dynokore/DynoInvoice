package com.dynokore.gst.submission.application.correction

import com.dynokore.gst.submission.domain.correction.OriginalStanding
import com.dynokore.gst.submission.domain.correction.StateClass

/** The decision on a delivered credit note version. */
sealed interface AmendmentVerdict {
    data object Proceed : AmendmentVerdict

    data object Refused : AmendmentVerdict
}

/**
 * Decides that a credit note version amends a credit note already netted into an original that was transmitted
 * (INV-REQ-558 behavior 6, owner option A of 2026-11-20). The refusal is an unapplied version plus a recorded finding,
 * never an HTTP answer. It runs in the caller's transaction and stores no mark and applies no transition.
 */
class NettedAmendmentGuard(
    private val originals: OriginalVersionSource,
    private val transitions: CreditNoteTransitions,
    private val creditNoteVersions: CreditNoteVersions,
    private val findings: NetFindings,
) {
    fun check(
        creditNoteDocUuid: String,
        version: Int,
    ): AmendmentVerdict {
        val original = originals.originalOf(creditNoteDocUuid) ?: return AmendmentVerdict.Proceed
        if (creditNoteDocUuid !in transitions.nettedBySystem(original.docUuid)) return AmendmentVerdict.Proceed
        val registered = creditNoteVersions.registeredVersionOf(creditNoteDocUuid) ?: return AmendmentVerdict.Proceed
        if (registered >= version) return AmendmentVerdict.Proceed
        val transmitted =
            originals
                .versionsOf(creditNoteDocUuid)
                .map(OriginalStanding::classOf)
                .any { it == StateClass.AT_IRAS || it == StateClass.SENT_OUT }
        if (!transmitted) return AmendmentVerdict.Proceed
        findings.record(
            NetFinding(NetFindingKind.AMENDED_AFTER_TRANSMISSION, creditNoteDocUuid, version, original.docUuid),
        )
        return AmendmentVerdict.Refused
    }

    /** Records one NET_NOT_POSITIVE finding per distinct credit note when [reason] is `NET_NOT_POSITIVE`. */
    fun recordNotPositive(
        creditNoteDocUuids: List<String>,
        reason: String,
    ) {
        if (reason != NET_NOT_POSITIVE) return
        creditNoteDocUuids.distinct().forEach {
            findings.record(NetFinding(NetFindingKind.NET_NOT_POSITIVE, it, null, null))
        }
    }

    private companion object {
        const val NET_NOT_POSITIVE = "NET_NOT_POSITIVE"
    }
}
