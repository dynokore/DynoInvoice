package gst.catalogue

/**
 * The VariationPoint catalogue artefact: a plain JSON rendering of every parsed row
 * (STK-61's "emits the catalogue artefact and the gst-catalogue Kotlin library" — the
 * same two-output shape [CatalogueArtefact] already uses for `ErrorCode`). Published
 * alongside the jar so a non-JVM consumer, or a human reviewing a diff, never needs to
 * parse Kotlin to see the catalogue.
 *
 * Hand-rolled for the same reason as [CatalogueArtefact]: one fixed flat shape, and
 * `jackson-databind`/`kotlinx-serialization-json` are each banned direct dependencies
 * (STK §1b(b), STK-08).
 */
object VariationPointCatalogueArtefact {
    fun render(
        rows: List<ParsedVariationPoint>,
        version: String,
    ): String =
        buildString {
            appendLine("{")
            appendLine("  \"catalogue\": \"gst-catalogue-variation-points\",")
            appendLine("  \"version\": ${version.jsonString()},")
            appendLine("  \"generatedFrom\": {")
            appendLine("    \"source\": \"Keel INV variation-point requirements\",")
            appendLine("    \"vendoredAt\": \"catalogue/variation-points/variation-points.yaml\"")
            appendLine("  },")
            appendLine("  \"variationPoints\": [")
            rows.forEachIndexed { index, row ->
                append("    ")
                append(rowJson(row))
                appendLine(if (index == rows.lastIndex) "" else ",")
            }
            appendLine("  ]")
            append("}")
        }

    private fun rowJson(row: ParsedVariationPoint): String {
        val default = row.default?.jsonString() ?: "null"
        val bounds = row.bounds?.jsonString() ?: "null"
        val statusNote = row.statusNote?.jsonString() ?: "null"
        val formDependence = row.formDependence?.jsonString() ?: "null"
        val modifiers = row.classModifiers.joinToString(", ") { "\"$it\"" }
        val scope = row.scope.joinToString(", ") { it.jsonString() }
        val setBy = row.setBy.joinToString(", ") { it.jsonString() }
        val options = row.options.joinToString(", ") { it.jsonString() }
        val governs = row.governs.joinToString(", ") { it.jsonString() }
        return "{ \"id\": ${row.id.jsonString()}, \"requirement\": ${row.requirement.jsonString()}, " +
            "\"revision\": ${row.revision.jsonString()}, \"title\": ${row.title.jsonString()}, " +
            "\"class\": \"${row.vpClass}\", \"classModifiers\": [$modifiers], " +
            "\"scope\": [$scope], \"owner\": ${row.owner.jsonString()}, \"setBy\": [$setBy], " +
            "\"mustBeElected\": ${row.mustBeElected}, \"default\": $default, \"options\": [$options], " +
            "\"bounds\": $bounds, \"status\": \"${row.status}\", \"statusNote\": $statusNote, " +
            "\"governs\": [$governs], \"formDependence\": $formDependence }"
    }

    private fun String.jsonString(): String {
        val escaped =
            this
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "")
                .replace("\t", "\\t")
        return "\"$escaped\""
    }
}
