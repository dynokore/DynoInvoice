package gst.catalogue

import java.io.File

/** One of the nine items INV-REQ-1247 behavior 4 fixes: never an option (INV-AC-18009). */
data class FixedItem(val id: String, val title: String)

/**
 * Negative list kept as data and compared by equality, deny by default (OWASP ASVS V1.4):
 * no variation-point row may be a fixed item of INV-REQ-1247 behavior 4.
 */
object FixedItemChecker {
    private const val HEADER = "id\ttitle"
    private val EXPECTED_IDS = (1..9).map { "FIXED-%02d".format(it) }

    fun load(file: File): List<FixedItem> {
        check(file.isFile) { "fixed items file missing: $file" }
        val lines = file.readLines().filter { it.isNotEmpty() }
        check(lines.firstOrNull() == HEADER) { "$file: header must be exactly 'id<TAB>title'" }
        val items = lines.drop(1).map { line ->
            val cells = line.split('\t')
            check(cells.size == 2) { "$file: row must have exactly two tab-separated cells: $line" }
            FixedItem(cells[0], cells[1])
        }
        check(items.size == EXPECTED_IDS.size) { "$file: expected ${EXPECTED_IDS.size} rows, found ${items.size}" }
        val dup = items.groupBy { it.id }.filterValues { it.size > 1 }.keys
        check(dup.isEmpty()) { "$file: duplicate id $dup" }
        check(items.map { it.id }.toSet() == EXPECTED_IDS.toSet()) {
            "$file: ids must be exactly FIXED-01 to FIXED-09, found ${items.map { it.id }}"
        }
        items.forEach { check(it.title.isNotBlank()) { "$file: blank title for ${it.id}" } }
        return items
    }

    fun check(items: List<FixedItem>, rows: List<ParsedVariationPoint>): List<String> =
        rows.flatMap { row ->
            items.mapNotNull { item ->
                when {
                    row.id == item.id ->
                        "row ${row.id} has the id of fixed item ${item.id}, which is never an option (INV-REQ-1247)"
                    row.title.trim().lowercase() == item.title.trim().lowercase() ->
                        "row ${row.id} is titled like fixed item ${item.id}, which is never an option (INV-REQ-1247)"
                    else -> null
                }
            }
        }
}
