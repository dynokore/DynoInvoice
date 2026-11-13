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
    fun record(
        tenantId: String,
        held: DocumentHeldFacts,
        raisedAt: Instant,
    ) {
        val overridden =
            store
                .rowsOfDocument(tenantId, held.docUuid)
                .filter { it.state == ItemState.OVERRIDDEN }
                .map { it.exceptionId }
                .toSet()
        store.upsert(ExceptionReadModel.opened(tenantId, held, raisedAt).filter { it.exceptionId !in overridden })
    }
}

/** Resolves the OPEN rows of the document whose code is in releasedHoldReasons. */
class RecordDocumentReleased(
    private val store: ExceptionStore,
) {
    fun record(
        tenantId: String,
        released: DocumentReleasedFacts,
    ) {
        val resolved = ExceptionReadModel.release(store.openRowsOfDocument(tenantId, released.docUuid), released)
        if (resolved.isNotEmpty()) store.upsert(resolved)
    }
}
