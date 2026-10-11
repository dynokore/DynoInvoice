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
                            "\"typedValue\": ${o.typedValue?.let { typedJson(it) } ?: "null"}, " +
                            "\"acknowledgedBy\": [${o.acknowledgedBy.joinToString(", ") { ackJson(it) }}] }"
                    } + "]",
                "\"multiSelect\": ${f.multiSelect}",
                "\"exclusiveKeys\": [${f.exclusiveKeys.joinToString(", ") { it.jsonString() }}]",
            )
        f.crossScope?.let { parts += "\"crossScope\": ${it.jsonString()}" }
        // crossOption and crossRow stay text, as in 1.x: the records' rules, joined (as the Kotlin library keeps them).
        f.crossOption.joinedRules()?.let { parts += "\"crossOption\": ${it.jsonString()}" }
        f.crossRow.joinedRules()?.let { parts += "\"crossRow\": ${it.jsonString()}" }
        // Every typed record, own row first, then cross-row, then cross-option, each with its placement (INV-CS-3604).
        val all = f.constraints + f.crossRow + f.crossOption
        if (all.isNotEmpty()) parts += "\"constraints\": [${all.joinToString(", ") { constraintJson(it) }}]"
        f.note?.let { parts += "\"note\": ${it.jsonString()}" }
        f.basis?.let { parts += "\"basis\": ${it.jsonString()}" }
        if (f.defaultByForm.isNotEmpty()) {
            parts += "\"defaultByForm\": { " +
                f.defaultByForm.entries.joinToString(", ") { (k, v) -> "${k.jsonString()}: ${v.jsonString()}" } + " }"
        }
        f.installationParameter?.let { parts += "\"installationParameter\": ${typedJson(it)}" }
        f.installationParameterRef?.let { parts += "\"installationParameterRef\": ${parameterRefJson(it)}" }
        f.secondApprover?.let { parts += "\"secondApprover\": ${it.jsonString()}" }
        return "{ " + parts.joinToString(", ") + " }"
    }

    private fun List<VpConstraint>.joinedRules(): String? = takeIf { it.isNotEmpty() }?.joinToString(" ") { it.rule }

    private fun factJson(f: ConstraintFact): String = "{ \"holder\": \"${f.holder}\", \"name\": ${f.name.jsonString()} }"

    /** One typed constraint record (INV-CS-3604), in the vendored YAML's shape with camelCase names. */
    private fun constraintJson(c: VpConstraint): String {
        val parts =
            mutableListOf(
                "\"id\": ${c.id.jsonString()}",
                "\"placement\": \"${c.placement.keelName}\"",
                "\"kind\": \"${c.kind}\"",
            )
        if (c.options.isNotEmpty()) parts += "\"options\": [${c.options.joinToString(", ") { it.jsonString() }}]"
        c.selection?.let { parts += "\"selection\": \"$it\"" }
        if (c.forms.isNotEmpty()) parts += "\"forms\": [${c.forms.joinToString(", ") { "\"$it\"" }}]"
        c.fact?.let { parts += "\"fact\": ${factJson(it)}" }
        c.row?.let { parts += "\"row\": ${it.jsonString()}" }
        if (c.rowOptions.isNotEmpty()) parts += "\"rowOptions\": [${c.rowOptions.joinToString(", ") { it.jsonString() }}]"
        c.at?.let { parts += "\"at\": \"$it\"" }
        if (c.rows.isNotEmpty()) parts += "\"rows\": [${c.rows.joinToString(", ") { it.jsonString() }}]"
        c.scopeLevel?.let { parts += "\"scopeLevel\": \"$it\"" }
        c.check?.let { parts += "\"check\": ${it.jsonString()}" }
        if (c.inputs.isNotEmpty()) {
            parts += "\"inputs\": [" +
                c.inputs.joinToString(", ") { i ->
                    val a = mutableListOf<String>()
                    i.row?.let { a += "\"row\": ${it.jsonString()}" }
                    i.fact?.let { a += "\"fact\": ${factJson(it)}" }
                    i.value?.let { a += "\"value\": ${it.jsonString()}" }
                    i.min?.let { a += "\"min\": ${boundJson(it)}" }
                    "{ " + a.joinToString(", ") + " }"
                } + "]"
        }
        parts += "\"rule\": ${c.rule.jsonString()}"
        parts += "\"refused\": ${c.refused.jsonString()}"
        parts += "\"source\": ${c.source.jsonString()}"
        return "{ " + parts.joinToString(", ") + " }"
    }

    private fun ackJson(a: Acknowledger): String =
        "{ \"roles\": [${a.roles.joinToString(", ") { it.jsonString() }}], \"condition\": \"${a.condition}\", " +
            "\"distinctFromSetter\": ${a.distinctFromSetter}, \"rolesByForm\": { " +
            a.rolesByForm.entries.joinToString(", ") { (k, r) -> "\"$k\": [${r.joinToString(", ") { it.jsonString() }}]" } + " } }"

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

    private fun boundJson(b: BoundValue): String =
        b.number?.toPlainString() ?: b.symbolic?.jsonString() ?: "{ \"row\": ${b.row!!.jsonString()} }"

    private fun parameterRefJson(p: InstallationParameterRef): String {
        val parts = mutableListOf("\"row\": ${p.row.jsonString()}", "\"requirement\": ${p.requirement.jsonString()}")
        p.name?.let { parts += "\"name\": ${it.jsonString()}" }
        p.note?.let { parts += "\"note\": ${it.jsonString()}" }
        p.rule?.let { parts += "\"rule\": ${it.jsonString()}" }
        return "{ " + parts.joinToString(", ") + " }"
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
