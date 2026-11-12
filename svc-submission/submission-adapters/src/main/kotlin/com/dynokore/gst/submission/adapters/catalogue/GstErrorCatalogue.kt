package com.dynokore.gst.submission.adapters.catalogue

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.submission.application.queue.CatalogueEntry
import com.dynokore.gst.submission.application.queue.ErrorCatalogue

/** [ErrorCatalogue] over the generated `ErrorCode` of gst-catalogue: read, never copied (INV-REQ-940). */
class GstErrorCatalogue : ErrorCatalogue {
    override fun entryOf(code: String): CatalogueEntry? =
        ErrorCode.fromCode(code)?.let { CatalogueEntry(it.code, it.owner, it.disposition?.name) }
}
