package com.dynokore.gst.reconciliation.domain.boxes

import com.dynokore.gst.reconciliation.domain.sgd.SgdLine
import java.math.BigDecimal

/** The document direction as the return-mapping pack and `routingFacts.direction` carry it. */
enum class Direction {
    SALE,
    PURCHASE,
}

/** Which allocated SGD amount of a line a Box takes. */
enum class Basis {
    TAXABLE_AMOUNT,
    TAX_AMOUNT,
}

data class BoxFeed(
    val box: String,
    val basis: Basis,
)

/** One entry of the return-mapping pack: the Boxes a line of this category and direction feeds. */
data class MappingEntry(
    val categoryCode: String,
    val direction: Direction,
    val boxes: List<BoxFeed>,
)

/** One version of a document and whether IRAS acknowledged its submission. */
data class VersionState(
    val docUuid: String,
    val version: Int,
    val acknowledged: Boolean,
)

data class DocumentLine(
    val docUuid: String,
    val version: Int,
    val direction: Direction,
    val line: SgdLine,
)

data class UnmappedLine(
    val docUuid: String,
    val version: Int,
    val lineNumber: Int,
    val taxCode: String,
)

/** Signed sums per Box at scale 2; a Box no line feeds has no key. */
data class BoxAggregation(
    val boxes: Map<String, BigDecimal>,
    val unmappedLines: List<UnmappedLine>,
)
