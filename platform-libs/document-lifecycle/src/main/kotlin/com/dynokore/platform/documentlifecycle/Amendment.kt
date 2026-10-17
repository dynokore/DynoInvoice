package com.dynokore.platform.documentlifecycle

/** What the host wants done for a class: an opaque outcome [code] and the opaque codes it wants [raised]. */
data class Outcome(
    val code: String,
    val raised: List<String> = emptyList(),
) {
    init {
        require(code.isNotBlank()) { "Outcome code must not be blank" }
        require(raised.none { it.isBlank() }) { "Raised codes must not be blank" }
    }
}

/**
 * Gives the class code of one changed field. [oldValue] or [newValue] is null when the field is present on one side
 * only; the whole payloads are passed so a classifier can look at other fields.
 */
fun interface FieldClassifier {
    fun classify(
        field: String,
        oldValue: String?,
        newValue: String?,
        oldPayload: Map<String, String>,
        newPayload: Map<String, String>,
    ): String

    companion object {
        fun fixed(classCode: String): FieldClassifier = FixedFieldClassifier(classCode)
    }
}

/** A classifier that always returns [classCode]; construction of a [ChangeTaxonomy] checks it against the classes. */
class FixedFieldClassifier internal constructor(
    val classCode: String,
) : FieldClassifier {
    override fun classify(
        field: String,
        oldValue: String?,
        newValue: String?,
        oldPayload: Map<String, String>,
        newPayload: Map<String, String>,
    ): String = classCode
}

/**
 * The amendment taxonomy as data. [classes] run from least to most restrictive. Every class has an outcome for a
 * transmitted version ([afterTransmission]) and one for a version not yet transmitted ([beforeTransmission]).
 * [classifiers] map a field name to its classifier; a changed field without one takes [unlistedClass], so a changed
 * field the host did not list is never ignored. Construction throws [IllegalArgumentException] for an invalid taxonomy.
 */
class ChangeTaxonomy(
    classes: List<String>,
    afterTransmission: Map<String, Outcome>,
    beforeTransmission: Map<String, Outcome>,
    classifiers: Map<String, FieldClassifier>,
    unlistedClass: String?,
) {
    val classes: List<String> = classes.toList()
    val afterTransmission: Map<String, Outcome> = afterTransmission.toMap()
    val beforeTransmission: Map<String, Outcome> = beforeTransmission.toMap()
    val classifiers: Map<String, FieldClassifier> = classifiers.toMap()
    val unlistedClass: String

    init {
        require(this.classes.isNotEmpty()) { "A taxonomy needs at least one class" }
        require(this.classes.none { it.isBlank() }) { "Class codes must not be blank" }
        require(this.classes.toSet().size == this.classes.size) { "Class codes must be unique: ${this.classes}" }
        val known = this.classes.toSet()
        for ((label, outcomes) in listOf(
            "afterTransmission" to this.afterTransmission,
            "beforeTransmission" to this.beforeTransmission,
        )) {
            for (c in this.classes) require(c in outcomes) { "Class '$c' has no $label outcome" }
            for (c in outcomes.keys) require(c in known) { "$label outcome names '$c', which is not a class" }
        }
        for ((field, classifier) in this.classifiers) {
            require(field.isNotBlank()) { "Classifier field names must not be blank" }
            if (classifier is FixedFieldClassifier) {
                require(
                    classifier.classCode in known,
                ) { "Classifier for '$field' names '${classifier.classCode}', not a class" }
            }
        }
        require(unlistedClass != null) { "unlistedClass is required: a changed field must never be ignored" }
        require(unlistedClass in known) { "unlistedClass '$unlistedClass' is not a class" }
        this.unlistedClass = unlistedClass
    }

    internal fun rank(classCode: String): Int = classes.indexOf(classCode)
}

/** One changed field with the class its classifier returned. A side is null when the field is absent there. */
data class ChangedField(
    val field: String,
    val oldValue: String?,
    val newValue: String?,
    val classCode: String,
)

/** A trace entry: a changed field, or (field null, values null) the entry naming the deciding class. */
data class TraceEntry(
    val field: String?,
    val oldValue: String?,
    val newValue: String?,
    val classCode: String,
)

/** The result of [classify]. When nothing changed, [decidingClass] and [outcome] are null and [trace] is empty. */
data class AmendmentDecision(
    val changed: Boolean,
    val fields: List<ChangedField>,
    val decidingClass: String?,
    val outcome: Outcome?,
    val trace: List<TraceEntry>,
)

/**
 * Classifies the difference between [lastAcknowledged] and [projected] (field name to value text). A field is changed
 * when its text differs; present on one side only counts as changed. The most restrictive class among the changed
 * fields decides; [transmitted] picks the after- or before-transmission outcome. Inputs are not modified.
 * Throws [IllegalStateException] naming the field and the code when a classifier returns a code not in the taxonomy.
 */
fun classify(
    lastAcknowledged: Map<String, String>,
    projected: Map<String, String>,
    transmitted: Boolean,
    taxonomy: ChangeTaxonomy,
): AmendmentDecision {
    val old = lastAcknowledged.toMap()
    val new = projected.toMap()
    val fields =
        (old.keys + new.keys).sorted().mapNotNull { field ->
            val o = old[field]
            val n = new[field]
            if (o == n) return@mapNotNull null
            val classifier = taxonomy.classifiers[field]
            val code = classifier?.classify(field, o, n, old, new) ?: taxonomy.unlistedClass
            check(taxonomy.rank(code) >= 0) { "Classifier for field '$field' returned '$code', which is not a class" }
            ChangedField(field, o, n, code)
        }
    if (fields.isEmpty()) return AmendmentDecision(false, emptyList(), null, null, emptyList())
    val deciding = fields.maxOf { taxonomy.rank(it.classCode) }.let { taxonomy.classes[it] }
    val outcome = (if (transmitted) taxonomy.afterTransmission else taxonomy.beforeTransmission).getValue(deciding)
    val trace =
        fields.map { TraceEntry(it.field, it.oldValue, it.newValue, it.classCode) } +
            TraceEntry(null, null, null, deciding)
    return AmendmentDecision(true, fields, deciding, outcome, trace)
}
