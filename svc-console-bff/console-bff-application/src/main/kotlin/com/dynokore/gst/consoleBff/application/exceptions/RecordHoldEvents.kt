package com.dynokore.gst.consoleBff.application.exceptions

import com.dynokore.gst.consoleBff.domain.badge.ItemState
import com.dynokore.gst.consoleBff.domain.exceptions.DocumentHeldFacts
import com.dynokore.gst.consoleBff.domain.exceptions.DocumentReleasedFacts
import com.dynokore.gst.consoleBff.domain.exceptions.ExceptionReadModel
import java.time.Instant

/** Opens one OPEN row per hold reason code; a stored OVERRIDDEN row is kept. */
class RecordDocumentHeld(
    private val store: ExceptionStore,
) {
    /** Returns true when at least one row stored differs from the stored row of its id or had none. */
    fun record(
        tenantId: String,
        held: DocumentHeldFacts,
        raisedAt: Instant,
    ): Boolean {
        val stored = store.rowsOfDocument(tenantId, held.docUuid).associateBy { it.exceptionId }
        val overridden =
            stored.values
                .filter { it.state == ItemState.OVERRIDDEN }
                .map { it.exceptionId }
                .toSet()
        val candidates = ExceptionReadModel.opened(tenantId, held, raisedAt).filter { it.exceptionId !in overridden }
        store.upsert(candidates)
        return candidates.any { stored[it.exceptionId] != it }
    }
}

/** Resolves the OPEN rows of the document whose code is in releasedHoldReasons. */
class RecordDocumentReleased(
    private val store: ExceptionStore,
) {
    /** Returns true when at least one OPEN row was resolved. */
    fun record(
        tenantId: String,
        released: DocumentReleasedFacts,
    ): Boolean {
        val resolved = ExceptionReadModel.release(store.openRowsOfDocument(tenantId, released.docUuid), released)
        if (resolved.isEmpty()) return false
        store.upsert(resolved)
        return true
    }
}
