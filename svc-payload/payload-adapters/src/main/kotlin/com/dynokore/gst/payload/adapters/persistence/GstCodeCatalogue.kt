package com.dynokore.gst.payload.adapters.persistence

import com.dynokore.gst.catalogue.Disposition
import com.dynokore.gst.catalogue.ErrorCode

/**
 * The catalogue disposition of a code over the generated `ErrorCode` of gst-catalogue (INV-Q-17169 A). A code is
 * OVERRIDABLE only when an entry whose `code` equals it by string equality has the disposition OVERRIDABLE; no entry,
 * a null disposition, MANUAL and TERMINAL are not (fail closed).
 */
class GstCodeCatalogue(
    private val entries: () -> List<CatalogueEntry> = {
        ErrorCode.entries.map {
            CatalogueEntry(
                it.code,
                it.disposition,
            )
        }
    },
) : CodeCatalogue {
    /** One catalogue row as this adapter reads it; the disposition may be absent. */
    data class CatalogueEntry(
        val code: String,
        val disposition: Disposition?,
    )

    override fun isOverridable(code: String): Boolean =
        entries().any { it.code == code && it.disposition == Disposition.OVERRIDABLE }
}
