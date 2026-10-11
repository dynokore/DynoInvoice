package gst.catalogue

/**
 * INV-T-3000: every row carries Keel's machine-readable catalogue fields, or says
 * exactly why not, and those fields agree with the row and with the rulings.
 *
 * Completeness: a row has a `catalogue` block, or is RETIRED (VP-27; Keel keeps it as a
 * published revision with status retired), or carries a pending marker in pending-fields.yaml naming the open INV-Q
 * question. A marker on a row that already has fields is stale and fails too.
 *
 * Consistency, for rows with fields:
 * - one option key per option text, in catalogue order (TPL-10), UPPER_SNAKE (G6 A),
 *   unique; at least one scope level, none repeated (INV-REQ-1242 b1);
 * - a must-be-elected row has no DEFAULT option; any other row has exactly one, at the
 *   default's own position where the default text is one of the options verbatim;
 * - strictness (INV-Q-2936): POLICY options are DEFAULT or NEUTRAL (G1 A); PARAMETER
 *   options are never LOOSER (G5 A); a CONTROL marked non-waivable (in full or in part)
 *   or tighten-only has no LOOSER option (INV-REQ-780 b1, INV-REQ-1244 b4);
 * - exclusive keys exist only on a multi-select row and name its own keys (G3 A);
 * - typed values (INV-Q-2934): an int, decimal, amount, money, rate or duration states its direction
 *   (a list item or map key/value takes its container's direction, so a list or map of numeric items states one); numeric min <= max (an
 *   exclusive min below max), a numeric default lies within them, min_items <= max_items;
 * - a per-form default (VP-74) replaces the single DEFAULT and names option keys.
 */
object VariationPointFieldsChecker {
    data class Violation(
        val id: String,
        val reason: String,
    ) {
        override fun toString(): String = "$id: $reason"
    }

    private val keyPattern = Regex("^[A-Z][A-Z0-9]*(_[A-Z0-9]+)*$")
    private val noLooserModifiers =
        setOf(VpClassModifier.NON_WAIVABLE, VpClassModifier.NON_WAIVABLE_IN_PART, VpClassModifier.TIGHTEN_ONLY)

    fun check(rows: List<ParsedVariationPoint>): List<Violation> = rows.flatMap { check(it) }

    fun check(row: ParsedVariationPoint): List<Violation> {
        val out = mutableListOf<Violation>()

        fun v(reason: String) = out.add(Violation(row.id, reason))
        val fields = row.fields
        if (fields == null) {
            if (row.status != VpStatus.RETIRED && row.pendingQuestions.isEmpty()) {
                v("Keel has no catalogue fields for this row and pending-fields.yaml names no open question for it")
            }
            return out
        }
        if (row.pendingQuestions.isNotEmpty()) {
            v("stale pending marker ${row.pendingQuestions.joinToString()}: Keel already carries the fields; remove it")
        }
        checkOptions(row, fields, ::v)
        checkStrictness(row, fields, ::v)
        if (fields.exclusiveKeys.isNotEmpty()) {
            if (!fields.multiSelect) v("exclusive_keys on a row that is not multi_select")
            val unknown = fields.exclusiveKeys - fields.options.map { it.key }.toSet()
            if (unknown.isNotEmpty()) v("exclusive_keys ${unknown.joinToString()} are not option keys")
        }
        fields.options.forEach { option -> option.typedValue?.let { checkTyped(it, option.key, ::v) } }
        // Keel states no direction for an installation parameter; only its bounds are checked.
        fields.installationParameter?.let { checkTyped(it, "installation_parameter", ::v, needsDirection = false) }
        return out
    }

    private fun checkOptions(
        row: ParsedVariationPoint,
        fields: VpCatalogueFields,
        v: (String) -> Boolean,
    ) {
        if (fields.scopeLevels.isEmpty()) v("no scope level")
        if (fields.scopeLevels.toSet().size != fields.scopeLevels.size) v("a scope level is repeated")
        val keys = fields.options.map { it.key }
        if (keys.size != row.options.size) {
            v("${keys.size} option keys for ${row.options.size} options (TPL-10: one key per option, in catalogue order)")
        }
        keys.filterNot { keyPattern.matches(it) }.forEach { v("option key '$it' is not UPPER_SNAKE") }
        if (keys.toSet().size != keys.size) v("option keys are not unique")
        if (fields.mustBeElected != null && fields.mustBeElected != row.mustBeElected) {
            v("catalogue must_be_elected=${fields.mustBeElected} disagrees with the row's ${row.mustBeElected}")
        }
        val defaults = fields.options.indices.filter { fields.options[it].strictness == Strictness.DEFAULT }
        if (fields.defaultByForm.isNotEmpty()) {
            if (defaults.isNotEmpty()) v("a row with a per-form default has a DEFAULT option")
            val unknown = fields.defaultByForm.values.toSet() - keys.toSet()
            if (unknown.isNotEmpty()) v("default_by_form names ${unknown.joinToString()}, which are not option keys")
        } else if (row.mustBeElected) {
            if (defaults.isNotEmpty()) v("a must-be-elected row has a DEFAULT option")
        } else if (defaults.size != 1) {
            v("${defaults.size} DEFAULT options; exactly one expected")
        } else {
            val textPosition = row.options.indexOf(row.default)
            if (textPosition >= 0 && textPosition != defaults.single()) {
                v("the DEFAULT option is at position ${defaults.single() + 1} but the default text is option ${textPosition + 1}")
            }
        }
    }

    private fun checkStrictness(
        row: ParsedVariationPoint,
        fields: VpCatalogueFields,
        v: (String) -> Boolean,
    ) {
        val looser = fields.options.filter { it.strictness == Strictness.LOOSER }.map { it.key }
        when (row.vpClass) {
            VpClass.POLICY -> {
                fields.options
                    .filter { it.strictness == Strictness.STRICTER || it.strictness == Strictness.LOOSER }
                    .forEach { v("POLICY option ${it.key} is ${it.strictness}; POLICY rows have no strictness (INV-Q-2936)") }
            }

            VpClass.PARAMETER -> {
                looser.forEach { v("PARAMETER option $it is LOOSER; a PARAMETER has no acknowledgement path (G5)") }
            }

            VpClass.CONTROL -> {
                val modifiers = row.classModifiers.filter { it in noLooserModifiers }
                if (modifiers.isNotEmpty()) {
                    looser.forEach { v("option $it is LOOSER on a ${modifiers.joinToString()} CONTROL (INV-REQ-780 b1)") }
                }
            }
        }
    }

    private val directed = setOf(ValueType.INT, ValueType.DECIMAL, ValueType.AMOUNT, ValueType.MONEY, ValueType.RATE, ValueType.DURATION)

    private fun carriesDirection(value: TypedValue): Boolean =
        value.type in directed || listOfNotNull(value.item, value.key, value.value).any { carriesDirection(it) }

    /**
     * [needsDirection] holds for an option's own typed value and for a record's fields
     * unless the record states a direction; the item of a LIST and the key/value of a
     * MAP take the container's direction.
     */
    private fun checkTyped(
        value: TypedValue,
        where: String,
        v: (String) -> Boolean,
        needsDirection: Boolean = true,
    ) {
        if (needsDirection && value.type in directed && value.direction == null) {
            v("$where: a ${value.type} typed value states no direction")
        }
        val lower = value.minExclusive?.number
        if (lower != null && value.max?.number != null && lower >= value.max.number) {
            v("$where: exclusive min $lower is not below max ${value.max.number}")
        }
        if (value.minItems != null && value.maxItems != null && value.minItems > value.maxItems) {
            v("$where: min_items ${value.minItems} exceeds max_items ${value.maxItems}")
        }
        if (needsDirection && (value.type == ValueType.LIST || value.type == ValueType.MAP) &&
            value.direction == null && listOfNotNull(value.item, value.key, value.value).any { carriesDirection(it) }
        ) {
            v("$where: a ${value.type} of numeric values states no direction")
        }
        value.item?.let { checkTyped(it, "$where.item", v, needsDirection = false) }
        value.key?.let { checkTyped(it, "$where.key", v, needsDirection = false) }
        value.value?.let { checkTyped(it, "$where.value", v, needsDirection = false) }
        val min = value.min?.number
        val max = value.max?.number
        val default = value.default?.number
        if (min != null && max != null && min > max) v("$where: min $min exceeds max $max")
        if (default != null && ((min != null && default < min) || (max != null && default > max))) {
            v("$where: default $default lies outside [${min ?: "-"}, ${max ?: "-"}]")
        }
        val exclusive = value.minExclusive?.number
        if (default != null && exclusive != null && default <= exclusive) {
            v("$where: default $default is not above exclusive min $exclusive")
        }
        // A record that states its own direction gives it to its fields (VP-76: NONE).
        val fieldsNeedDirection = needsDirection && value.direction == null
        value.fields.forEach { (name, field) -> checkTyped(field, "$where.$name", v, fieldsNeedDirection) }
    }
}
