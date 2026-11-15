package com.dynokore.gst.canonical.application.document

import java.time.LocalDate

/**
 * Decorator over [ScopeEvaluation] (open-closed): it returns the delegate's answer unchanged and keeps the last
 * [ScopeAnswer.Verdict] with its command in a single thread-bound slot, so the facts source can read the facts of
 * the scope answer without a changed signature. The record, the builder and the facts source run in one thread
 * inside the record transaction. Every [evaluate] and every [take] clears the slot, so nothing accumulates.
 */
class ScopeAnswerCapture(
    private val delegate: ScopeEvaluation,
) : ScopeEvaluation {
    private class Held(
        val command: RecordDocumentCommand,
        val verdict: ScopeAnswer.Verdict,
    )

    private val slot = ThreadLocal<Held?>()

    override fun evaluate(
        command: RecordDocumentCommand,
        periodRef: String?,
        transactionDate: LocalDate,
    ): ScopeAnswer {
        slot.remove()
        val answer = delegate.evaluate(command, periodRef, transactionDate)
        if (answer is ScopeAnswer.Verdict) slot.set(Held(command, answer))
        return answer
    }

    /** The captured verdict when it was captured for [command] (data-class equality); the slot is always cleared. */
    fun take(command: RecordDocumentCommand): ScopeAnswer.Verdict? {
        val held = slot.get()
        slot.remove()
        return held?.takeIf { it.command == command }?.verdict
    }
}
