package com.dynokore.gst.rules.domain.coding

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.rules.domain.pack.SourceClass

/** One code of the code catalogue pack (INV-REQ-2429): the Annex E group, side and rate, and its citation. */
data class CatalogueEntry(
    val code: String,
    val group: String,
    val side: String,
    val annexERate: String,
    val submissionRate: String?,
    val sourceClass: SourceClass?,
    val sourceReference: String?,
)

/**
 * The code catalogue read from pack data, never an enumerated type (INV-REQ-418 b1). [complete] is carried for the
 * record only: no decision of the coding reads it (INV-REQ-418 b3, INV-AC-1439).
 */
class CodeCatalogue(
    val complete: Boolean,
    entries: List<CatalogueEntry>,
) {
    val entries: List<CatalogueEntry> = entries.toList()
    private val byCode: Map<String, CatalogueEntry> = entries.associateBy { it.code }

    init {
        require(byCode.size == entries.size) { "a code occurs twice in the catalogue" }
    }

    fun contains(code: String): Boolean = code in byCode

    fun entryOf(code: String): CatalogueEntry? = byCode[code]
}

/** A mapping entry as the coding reads it: the tenant's internal code, the IRAS code and the optional scheme tag. */
data class MappingEntry(
    val internalTaxCode: String,
    val irasCode: String,
    val schemeTag: String? = null,
)

/** A refusal of one authored entry, naming it (INV-AC-1508: "the offending entry named"). */
data class CodeFinding(
    val internalTaxCode: String,
    val irasCode: String,
    val code: ErrorCode,
)

/**
 * The checks on the IRAS codes of a mapping pack draft, by the stage that owns them.
 *
 * Authoring (INV-REQ-497 b2, RUL-010): a code that contains the namespace separator `:` is refused, the entry named,
 * never normalised. This is the published RUL-010 text; [CODE_SHAPE] is not the test for it, so `sr`, `SR ` and
 * `gst-SR` are not RUL-010 (INV-Q-11620 ruling 3). `SRX` is not namespaced and so passes authoring.
 *
 * Sign-off (INV-REQ-2429, RUL-009): an entry whose code is absent from the catalogue of the pack's version, by string
 * equality, is refused and no override exists. A namespaced code is RUL-010's at authoring and is not repeated here.
 */
object MappingCodeChecks {
    private const val NAMESPACE_SEPARATOR = ':'
    private val CODE_SHAPE = Regex("^[A-Z][A-Z0-9]{0,9}(-[A-Z0-9]{1,9})?$")

    /** The catalogue's own code shape; kept for callers that need it, not the test for RUL-010. */
    fun isBare(code: String): Boolean = CODE_SHAPE.matches(code)

    private fun isNamespaced(code: String): Boolean = NAMESPACE_SEPARATOR in code

    fun atAuthoring(entries: List<MappingEntry>): List<CodeFinding> =
        entries
            .filter { isNamespaced(it.irasCode) }
            .map { CodeFinding(it.internalTaxCode, it.irasCode, ErrorCode.RUL_010) }

    fun atSignOff(
        entries: List<MappingEntry>,
        catalogue: CodeCatalogue,
    ): List<CodeFinding> =
        entries
            .filter { !isNamespaced(it.irasCode) && !catalogue.contains(it.irasCode) }
            .map { CodeFinding(it.internalTaxCode, it.irasCode, ErrorCode.RUL_009) }
}
