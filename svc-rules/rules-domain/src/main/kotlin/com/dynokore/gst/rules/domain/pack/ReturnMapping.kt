package com.dynokore.gst.rules.domain.pack

import com.dynokore.gst.rules.domain.coding.Direction

/** The amount of a category that feeds a return Box (return-mapping-pack-contents.schema.json). */
enum class BoxBasis { TAXABLE_AMOUNT, TAX_AMOUNT }

/** A return Box an entry feeds, with the amount that feeds it. */
data class ReturnBox(
    val box: String,
    val basis: BoxBasis,
)

/**
 * One entry of a RETURN_MAPPING pack (INV-REQ-654, INV-AC-7968): the category code and direction mapped to the Boxes
 * it feeds, with the source class and citation. Nothing upgrades the class (INV-AC-7970).
 */
data class ReturnMappingEntry(
    val categoryCode: String,
    val direction: Direction,
    val boxes: List<ReturnBox>,
    val sourceClass: SourceClass,
    val sourceReference: String,
) {
    /** The entry as the sign-off gate reads it: `<categoryCode>/<direction>`, the citation and the class. */
    fun toPackEntry(): PackEntry = PackEntry("$categoryCode/${direction.name}", sourceReference, sourceClass)
}
