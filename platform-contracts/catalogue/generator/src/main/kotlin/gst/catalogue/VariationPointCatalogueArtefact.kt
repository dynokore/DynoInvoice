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
            "\"governs\": [$governs], \"formDependence\": $formDependence, " +
            "\"catalogueFields\": ${row.fields?.let { fieldsJson(it) } ?: "null"}, " +
            "\"pendingQuestions\": [${row.pendingQuestions.joinToString(", ") { it.jsonString() }}] }"
    }

    /** Keel's catalogue fields (INV-T-3000), in the same shape the vendored YAML carries them. */
    private fun fieldsJson(f: VpCatalogueFields): String {
        val parts =
            mutableListOf(
                "\"scopeLevels\": [${f.scopeLevels.joinToString(", ") { "\"$it\"" }}]",
                "\"options\": [" +
                    f.options.joinToString(", ") { o ->
                        "{ \"key\": ${o.key.jsonString()}, \"strictness\": \"${o.strictness}\", " +
                            "\"typedValue\": ${o.typedValue?.let { typedJson(it) } ?: "null"} }"
                    } + "]",
                "\"multiSelect\": ${f.multiSelect}",
                "\"exclusiveKeys\": [${f.exclusiveKeys.joinToString(", ") { it.jsonString() }}]",
            )
        f.crossScope?.let { parts += "\"crossScope\": ${it.jsonString()}" }
        f.crossOption?.let { parts += "\"crossOption\": ${it.jsonString()}" }
        f.crossRow?.let { parts += "\"crossRow\": ${it.jsonString()}" }
        f.note?.let { parts += "\"note\": ${it.jsonString()}" }
        f.basis?.let { parts += "\"basis\": ${it.jsonString()}" }
        if (f.defaultByForm.isNotEmpty()) {
            parts += "\"defaultByForm\": { " +
                f.defaultByForm.entries.joinToString(", ") { (k, v) -> "${k.jsonString()}: ${v.jsonString()}" } + " }"
        }
        f.installationParameter?.let { parts += "\"installationParameter\": ${typedJson(it)}" }
        f.secondApprover?.let { parts += "\"secondApprover\": ${it.jsonString()}" }
        return "{ " + parts.joinToString(", ") + " }"
    }

    private fun typedJson(t: TypedValue): String {
        val parts = mutableListOf("\"type\": \"${t.type.keelName}\"")
        t.name?.let { parts += "\"name\": ${it.jsonString()}" }
        t.unit?.let { parts += "\"unit\": ${it.jsonString()}" }
        t.min?.let { parts += "\"min\": ${boundJson(it)}" }
        t.max?.let { parts += "\"max\": ${boundJson(it)}" }
        t.default?.let { parts += "\"default\": ${boundJson(it)}" }
        t.direction?.let { parts += "\"direction\": \"$it\"" }
        t.ref?.let { parts += "\"ref\": ${it.jsonString()}" }
        t.filter?.let { parts += "\"filter\": ${it.jsonString()}" }
        t.ordered?.let { parts += "\"ordered\": $it" }
        t.requires?.let { parts += "\"requires\": ${it.jsonString()}" }
        if (t.fields.isNotEmpty()) {
            parts += "\"fields\": { " + t.fields.entries.joinToString(", ") { (k, v) -> "${k.jsonString()}: ${typedJson(v)}" } + " }"
        }
        t.minExclusive?.let { parts += "\"minExclusive\": ${boundJson(it)}" }
        t.item?.let { parts += "\"item\": ${typedJson(it)}" }
        t.key?.let { parts += "\"key\": ${typedJson(it)}" }
        t.value?.let { parts += "\"value\": ${typedJson(it)}" }
        if (t.values.isNotEmpty()) parts += "\"values\": [${t.values.joinToString(", ") { it.jsonString() }}]"
        t.distinct?.let { parts += "\"distinct\": $it" }
        t.minItems?.let { parts += "\"minItems\": $it" }
        t.maxItems?.let { parts += "\"maxItems\": $it" }
        t.length?.let { parts += "\"length\": ${boundJson(it)}" }
        t.rule?.let { parts += "\"rule\": ${it.jsonString()}" }
        t.comparedWith?.let { parts += "\"comparedWith\": ${it.jsonString()}" }
        return "{ " + parts.joinToString(", ") + " }"
    }

    private fun boundJson(b: BoundValue): String = b.number?.toPlainString() ?: b.symbolic!!.jsonString()

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
