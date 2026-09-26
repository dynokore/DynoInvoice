package gst.catalogue

/**
 * The catalogue artefact: a plain JSON rendering of every parsed row, independent of
 * the generated Kotlin (STK-61's "emits the catalogue artefact and the gst-catalogue
 * Kotlin library" — two outputs of one generation, not one). This is what a future
 * train's [StabilityChecker] compares against (INV-AC-2170) and what CI publishes as
 * `gst-catalogue-error-codes-<version>.json` alongside the jar, so a non-JVM consumer
 * (or a human reviewing a diff) never needs to parse Kotlin to see the catalogue.
 *
 * Hand-rolled rather than pulled in through a JSON library: this generator has one
 * simple, fixed shape to write, and `com.fasterxml.jackson.core:jackson-databind` is
 * a banned direct dependency (STK §1b(b)) while `kotlinx-serialization-json` is
 * banned outright (STK-08) — pulling either in for one flat object would be the
 * "shared model jar" STK-46 already rejects, in miniature.
 */
object CatalogueArtefact {
    fun render(
        rows: List<ParsedErrorCode>,
        version: String,
        sourceCommit: String,
    ): String =
        buildString {
            appendLine("{")
            appendLine("  \"catalogue\": \"gst-catalogue-error-codes\",")
            appendLine("  \"version\": ${version.jsonString()},")
            appendLine("  \"generatedFrom\": {")
            appendLine("    \"repo\": \"invoice-docs\",")
            appendLine("    \"path\": \"model/architecture/error-codes.yaml\",")
            appendLine("    \"commit\": ${sourceCommit.jsonString()}")
            appendLine("  },")
            appendLine("  \"codes\": [")
            rows.forEachIndexed { index, row ->
                append("    ")
                append(rowJson(row))
                appendLine(if (index == rows.lastIndex) "" else ",")
            }
            appendLine("  ]")
            append("}")
        }

    private fun rowJson(row: ParsedErrorCode): String {
        val disposition = row.disposition?.let { "\"$it\"" } ?: "null"
        // revisionNote travels with the artefact (not just the generated Kotlin,
        // which has no use for it) because the *next* train's stability check
        // (INV-AC-2170, StabilityChecker) needs it to tell a documented severity/
        // disposition revision (INV-REQ-1183 behaviour #3) from an undocumented,
        // disallowed repurposing.
        val revisionNote = row.revisionNote?.jsonString() ?: "null"
        return "{ \"code\": ${row.code.jsonString()}, \"condition\": ${row.conditionCore.jsonString()}, " +
            "\"severity\": \"${row.severity}\", \"disposition\": $disposition, " +
            "\"owner\": ${row.owner.jsonString()}, \"revisionNote\": $revisionNote }"
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
