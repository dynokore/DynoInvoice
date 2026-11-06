package com.dynokore.gst.payload.adapters.persistence

import com.dynokore.gst.payload.application.finding.FindingsReader
import com.dynokore.gst.payload.application.override.BlockingFindings
import com.dynokore.gst.payload.application.override.FindingDisposition
import java.util.UUID

/** The catalogue disposition of a code (error catalogue of platform-contracts); a code it does not hold has none. */
fun interface CodeCatalogue {
    /** True only when the catalogue holds the code with the disposition OVERRIDABLE. */
    fun isOverridable(code: String): Boolean
}

/**
 * Reads the stored findings of a version through [FindingsReader] (INV-T-17036). The code is OVERRIDABLE only when a
 * stored finding of that exact version carries it by string equality, every stored finding of it (one per field) has
 * `overridable` true and the catalogue says OVERRIDABLE. Anything else, an unreadable store included, is refused.
 */
class StoredBlockingFindings(
    private val reader: FindingsReader,
    private val catalogue: CodeCatalogue,
) : BlockingFindings {
    override fun disposition(
        docUuid: UUID,
        version: Int,
        code: String,
    ): FindingDisposition =
        runCatching {
            val carried = reader.blocking(docUuid, version).filter { it.code == code }
            if (carried.isNotEmpty() && carried.all { it.overridable } && catalogue.isOverridable(code)) {
                FindingDisposition.OVERRIDABLE
            } else {
                FindingDisposition.REFUSED
            }
        }.getOrDefault(FindingDisposition.REFUSED)
}
