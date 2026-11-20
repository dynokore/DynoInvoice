package com.dynokore.gst.submission.application.correction

import com.dynokore.gst.submission.application.register.NetDerivationData
import com.dynokore.gst.submission.domain.correction.CorrectionDecision
import com.dynokore.gst.submission.domain.correction.CorrectionReason
import com.dynokore.gst.submission.domain.correction.CreditNoteCorrection
import com.dynokore.gst.submission.domain.correction.OriginalStanding
import com.dynokore.gst.submission.domain.correction.StateClass
import com.dynokore.gst.submission.domain.ordering.LifecycleState

/**
 * Applies the correction decision of a received purchase credit note (INV-REQ-558 behavior 6, INV-REQ-559) and the
 * outcomes of the net derivation. It runs in the caller's transaction. Zero or negative net is refused upstream as
 * `NET_NOT_POSITIVE` and arrives here as a refusal (open owner question, fail-closed).
 */
@Suppress("LongParameterList") // one required port each, no defaults
class CreditNoteCorrectionHandler(
    private val originals: OriginalVersionSource,
    private val declarations: NettingDeclarations,
    private val transitions: CreditNoteTransitions,
    private val requests: NetDerivationRequestPort,
    private val refusals: NetRefusedMarks,
    private val recorder: NettingDeclarationRecorder,
    private val findings: NetFindings,
) {
    /** Decides the credit note and applies the result once. */
    fun decide(creditNoteDocUuid: String) {
        val versions = originals.versionsOf(creditNoteDocUuid)
        val decision =
            CreditNoteCorrection.decide(
                versions,
                declarations.declared(creditNoteDocUuid),
                refusals.isRefused(creditNoteDocUuid),
            )
        when (decision) {
            // The entry stays SUBMITTABLE; the submission path holds or releases it (INV-REQ-559).
            is CorrectionDecision.SubmitSeparately,
            CorrectionDecision.SubmitAsPosted,
            CorrectionDecision.Wait,
            -> Unit

            is CorrectionDecision.NotSubmittable -> transitions.notSubmittable(creditNoteDocUuid, decision.reason)

            CorrectionDecision.NetIntoOriginal -> requestNet(creditNoteDocUuid)
        }
    }

    private fun requestNet(creditNoteDocUuid: String) {
        val original =
            checkNotNull(originals.originalOf(creditNoteDocUuid)) { "net into original: no resolved original" }
        requests.request(original.docUuid, original.currentVersion, listOf(creditNoteDocUuid))
    }

    /**
     * A derived version of [originalDocUuid] arrived. Each credit note it names becomes NOT_SUBMITTABLE with
     * NETTED_BY_SYSTEM, once. When a version of the original was already sent, the derived version is cancelled by
     * the revision path and never submitted, so the credit note is neither marked nor netted: it is decided again
     * (no `netRefused` mark), and the sent row applies.
     */
    fun onDerivedVersion(
        originalDocUuid: String,
        netDerivation: NetDerivationData,
    ) {
        val already = transitions.nettedBySystem(originalDocUuid).toSet()
        netDerivation.creditNotes.map { it.docUuid }.distinct().filter { it !in already }.forEach { creditNote ->
            if (originalWasSent(originals.versionsOf(creditNote))) {
                decide(creditNote)
            } else {
                transitions.notSubmittable(creditNote, CorrectionReason.NETTED_BY_SYSTEM)
            }
        }
    }

    /**
     * The derivation of [creditNoteDocUuids] was refused: with reason `NET_NOT_POSITIVE` record one finding per
     * distinct credit note (owner option A of 2026-11-20), then store `netRefused` and decide again.
     */
    fun onRefused(
        creditNoteDocUuids: List<String>,
        reason: String,
    ) {
        creditNoteDocUuids.distinct().forEach {
            if (reason == NET_NOT_POSITIVE) {
                findings.record(NetFinding(NetFindingKind.NET_NOT_POSITIVE, it, null, null))
            }
            refusals.store(it)
            decide(it)
        }
    }

    /**
     * A version of [originalDocUuid] arrived. One with a [netDerivation] is a derived version. One without it, that
     * does not declare a credit note currently NETTED_BY_SYSTEM (an ERP revision whose re-derivation was refused),
     * stores `netRefused` for that credit note, lifts it and decides again. An ERP revision first records its declared
     * netting set ([recorder]).
     */
    @Suppress("UnusedParameter") // the version is part of the event key; the decision reads the register
    fun onOriginalVersion(
        originalDocUuid: String,
        version: Int,
        netDerivation: NetDerivationData?,
        declaredKeys: Set<String>,
    ) {
        if (netDerivation != null) {
            onDerivedVersion(originalDocUuid, netDerivation)
            return
        }
        recorder.replaceDeclared(originalDocUuid, declaredKeys)
        transitions.nettedBySystem(originalDocUuid).filter { it !in declaredKeys }.forEach {
            refusals.store(it)
            transitions.lift(it)
            decide(it)
        }
    }

    private fun originalWasSent(versions: List<LifecycleState>): Boolean =
        versions.map(OriginalStanding::classOf).any { it == StateClass.AT_IRAS || it == StateClass.SENT_OUT }

    private companion object {
        const val NET_NOT_POSITIVE = "NET_NOT_POSITIVE"
    }
}
