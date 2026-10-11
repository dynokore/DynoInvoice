package gst.catalogue

/**
 * Checks every profile value against the VariationPoint catalogue and the error-code
 * catalogue of the same gst-catalogue (INV-T-3277; INV-Q-3233, INV-Q-3285 A″). It fails on:
 * - a value for a variation point that is not in the catalogue, is retired, or belongs to
 *   another requirement than the value names;
 * - an option key (option, options, otherwise_option, alternatives, needs_acknowledgement)
 *   that is not an option of that row, or `option`/`options` used on the wrong kind of row
 *   (single- or multi-select), or an exclusive key combined with others;
 * - a typed value whose kind or unit differs from its option's typed value or which lies
 *   outside its numeric bounds (INV-Q-2934);
 * - an `adds` entry for an option that takes error codes, when the code is not in the
 *   error-code catalogue or is not of the severity the option's filter names;
 * - a LOOSER option the value can select that is not flagged for acknowledgement, or a
 *   flagged option that is not LOOSER (INV-REQ-1244, INV-Q-2936);
 * - `explicit_default` on an option that is not the row's DEFAULT.
 * An unresolved (untyped) value is checked only for its variation point and requirement:
 * it has no option to check. Pure (no I/O).
 */
object OperatingProfileChecker {
    private const val ERROR_CODES_REF = "error-codes catalogue"
    private val severityFilter = Regex("^severity ([A-Z]+) only$")

    fun check(
        profiles: List<ParsedOperatingProfile>,
        variationPoints: List<ParsedVariationPoint>,
        errorCodes: List<ParsedErrorCode>,
    ): List<String> {
        val vps = variationPoints.associateBy { it.id }
        val codes = errorCodes.associateBy { it.code }
        return profiles.flatMap { p -> p.values.flatMap { v -> checkValue("${p.id} ${v.vp}", v, vps[v.vp], codes) } }
    }

    private fun checkValue(
        where: String,
        v: ParsedProfileValue,
        vp: ParsedVariationPoint?,
        codes: Map<String, ParsedErrorCode>,
    ): List<String> {
        if (vp == null) return listOf("$where: no such variation point in the catalogue")
        val out = mutableListOf<String>()
        if (vp.requirement != v.requirement) out += "$where: names ${v.requirement}, but ${vp.id} is ${vp.requirement}"
        if (vp.status == VpStatus.RETIRED) out += "$where: ${vp.id} is retired and never offered (INV-REQ-1251 b4)"
        if (v.unresolved != null) return out
        val fields = vp.fields ?: return out + "$where: ${vp.id} has no option keys in the catalogue yet"
        val specs = fields.options.associateBy { it.key }
        v.referencedKeys.distinct().filter { it !in specs }.forEach { out += "$where: '$it' is not an option of ${vp.id}" }
        if (fields.multiSelect && v.option != null) out += "$where: ${vp.id} is multi-select; use 'options'"
        if (!fields.multiSelect && v.options.isNotEmpty()) out += "$where: ${vp.id} is single-select; use 'option'"
        val exclusive = v.options.filter { it in fields.exclusiveKeys }
        if (exclusive.isNotEmpty() && v.options.size > 1) out += "$where: exclusive key(s) $exclusive combined with others"
        if (out.isNotEmpty()) return out

        val selectable = (listOfNotNull(v.option, v.otherwiseOption) + v.options + v.alternatives).distinct()
        val looser = selectable.filter { specs.getValue(it).strictness == Strictness.LOOSER }
        (looser - v.needsAcknowledgement.toSet()).forEach { out += "$where: LOOSER option $it is not flagged needs_acknowledgement" }
        v.needsAcknowledgement.filter { specs.getValue(it).strictness != Strictness.LOOSER }.forEach {
            out += "$where: $it is flagged needs_acknowledgement but is not LOOSER"
        }
        if (v.explicitDefault) {
            val chosen = listOfNotNull(v.option) + v.options
            chosen.filter { specs.getValue(it).strictness != Strictness.DEFAULT }.forEach {
                out += "$where: explicit_default on $it, which is not the DEFAULT option of ${vp.id}"
            }
        }
        v.typedValue?.let { out += checkTyped(where, it, v.option?.let { o -> specs.getValue(o).typedValue }) }
        out += checkAdds(where, v, specs, codes)
        return out
    }

    private fun checkTyped(
        where: String,
        t: ProfileTypedValue,
        spec: TypedValue?,
    ): List<String> {
        if (spec == null) return listOf("$where: carries a typed value, but its option has none")
        val out = mutableListOf<String>()
        if (spec.type != t.type) out += "$where: typed value is ${t.type}, its option takes ${spec.type}"
        if (spec.unit != null && spec.unit != t.unit) out += "$where: typed value unit '${t.unit}', its option takes '${spec.unit}'"
        fun bound(
            name: String,
            b: BoundValue?,
            ok: (Int) -> Boolean,
        ) {
            if (b == null) return
            val n = b.number ?: return run { out += "$where: $name bound '${b.symbolic}' is not numeric and cannot be checked" }
            if (!ok(t.value.compareTo(n))) out += "$where: typed value ${t.value.toPlainString()} violates $name ${n.toPlainString()}"
        }
        bound("min", spec.min) { it >= 0 }
        bound("max", spec.max) { it <= 0 }
        bound("min_exclusive", spec.minExclusive) { it > 0 }
        return out
    }

    private fun checkAdds(
        where: String,
        v: ParsedProfileValue,
        specs: Map<String, VpOptionSpec>,
        codes: Map<String, ParsedErrorCode>,
    ): List<String> {
        if (v.adds.isEmpty()) return emptyList()
        val chosen = (listOfNotNull(v.option) + v.options).mapNotNull { specs.getValue(it).typedValue }
        val codeSpec = chosen.find { it.ref == ERROR_CODES_REF } ?: return emptyList() // not codes: carried as the row's text
        val severity =
            codeSpec.filter?.let { f ->
                severityFilter.find(f)?.groupValues?.get(1)?.let { s -> Severity.entries.find { it.name == s } }
                    ?: return listOf("$where: option filter '$f' is not understood; extend the checker")
            }
        return v.adds.mapNotNull { code ->
            val row = codes[code]
            when {
                row == null -> "$where: adds $code, which is not in the error-code catalogue"
                severity != null && row.severity != severity -> "$where: adds $code (${row.severity}), but the option takes $severity only"
                else -> null
            }
        }
    }
}
