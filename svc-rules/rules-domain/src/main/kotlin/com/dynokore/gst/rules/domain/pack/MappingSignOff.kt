package com.dynokore.gst.rules.domain.pack

import com.dynokore.gst.catalogue.ErrorCode

/**
 * The sign-off gate of a tenant mapping pack (INV-AC-755, FR-AA.17): an entry whose source class is SECONDARY or
 * INFERRED blocks sign-off with RUL-007 until it is re-sourced to PRIMARY or explicitly accepted. An entry without a
 * class counts as unclassified and blocks too (coordinator ruling INV-Q-6477: never read as PRIMARY).
 */
object MappingSignOff {
    sealed interface Outcome {
        data object SignedOff : Outcome

        data class Blocked(
            val code: ErrorCode,
            val entryIds: List<String>,
        ) : Outcome
    }

    fun attempt(
        entries: List<PackEntry>,
        acceptedEntryIds: Set<String> = emptySet(),
    ): Outcome {
        val blocking =
            entries.filter { it.sourceClass != SourceClass.PRIMARY && it.entryId !in acceptedEntryIds }.map { it.entryId }
        return if (blocking.isEmpty()) Outcome.SignedOff else Outcome.Blocked(ErrorCode.RUL_007, blocking)
    }
}
