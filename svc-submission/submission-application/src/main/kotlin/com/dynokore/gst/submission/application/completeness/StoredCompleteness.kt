package com.dynokore.gst.submission.application.completeness

import com.dynokore.gst.submission.domain.readiness.CompletenessState

/** Derives the completeness state of an entity and period from its stored rows (ruled case 6). Pure. */
object StoredCompleteness {
    fun derive(rows: List<StoredRow>): CompletenessState {
        val failing =
            rows
                .filter { it.state == StoredState.FAILING }
                .map { it.controlId }
                .distinct()
                .sorted()
        return when {
            failing.isNotEmpty() -> CompletenessState.Failed(failing)
            rows.isEmpty() || rows.any { it.state == StoredState.OPEN } -> CompletenessState.Unavailable
            else -> CompletenessState.Passed
        }
    }
}
