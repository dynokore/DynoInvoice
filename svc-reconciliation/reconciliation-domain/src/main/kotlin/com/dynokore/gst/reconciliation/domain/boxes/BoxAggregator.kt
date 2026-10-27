package com.dynokore.gst.reconciliation.domain.boxes

import java.math.BigDecimal

/** Version choice and per-Box aggregation of the submitted side of a run (INV-Q-7239 A). */
object BoxAggregator {
    private const val SCALE = 2

    /** Per docUuid with an acknowledged state: its highest acknowledged version. */
    fun submittedVersions(states: List<VersionState>): Map<String, Int> =
        states
            .filter { it.acknowledged }
            .groupBy { it.docUuid }
            .mapValues { (_, acknowledged) -> acknowledged.maxOf { it.version } }

    /** Per docUuid with no acknowledged state: its highest version. */
    fun unsubmittedCurrentVersions(states: List<VersionState>): Map<String, Int> =
        states
            .groupBy { it.docUuid }
            .filterValues { group -> group.none { it.acknowledged } }
            .mapValues { (_, group) -> group.maxOf { it.version } }

    fun aggregate(
        entries: List<MappingEntry>,
        lines: List<DocumentLine>,
    ): BoxAggregation {
        val byKey = entries.associateBy { it.categoryCode to it.direction }
        require(byKey.size == entries.size) { "two entries have the same (categoryCode, direction)" }
        entries.forEach { entry ->
            require(
                entry.boxes
                    .map { it.box }
                    .toSet()
                    .size == entry.boxes.size,
            ) {
                "entry ${entry.categoryCode}/${entry.direction} lists a box twice"
            }
        }
        val sums = mutableMapOf<String, BigDecimal>()
        val unmapped = mutableListOf<UnmappedLine>()
        for (line in lines) {
            val entry = byKey[line.line.taxCode to line.direction]
            if (entry == null) {
                unmapped += UnmappedLine(line.docUuid, line.version, line.line.lineNumber, line.line.taxCode)
                continue
            }
            for (feed in entry.boxes) {
                val amount =
                    when (feed.basis) {
                        Basis.TAXABLE_AMOUNT -> line.line.taxableAmountSgd
                        Basis.TAX_AMOUNT -> line.line.taxAmountSgd
                    }
                require(amount.scale() <= SCALE || amount.stripTrailingZeros().scale() <= SCALE) {
                    "amount $amount has more than $SCALE decimal places"
                }
                sums.merge(feed.box, amount.setScale(SCALE)) { a, b -> a + b }
            }
        }
        return BoxAggregation(
            boxes = sums.toSortedMap(),
            unmappedLines =
                unmapped.sortedWith(compareBy({ it.docUuid }, { it.version }, { it.lineNumber })),
        )
    }
}
