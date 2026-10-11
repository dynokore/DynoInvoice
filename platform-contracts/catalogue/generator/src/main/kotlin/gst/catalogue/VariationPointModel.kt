package gst.catalogue

/**
 * INV-REQ-1239 (FR-AI.1) behaviour #1's fixed class vocabulary for a variation point.
 * `LAW` is deliberately absent: it "is never a tenant option" (behaviour #1), so no
 * `LAW`-classed entity is ever tagged as a variation point (source ID `VP-nn`) in the
 * first place — there is nothing for this enum to represent.
 */
enum class VpClass {
    POLICY,
    CONTROL,
    PARAMETER,
}

/**
 * A modifier on [VpClass], read off the trailing comma-separated part of the vendored
 * row's `class` field (e.g. "CONTROL, non-waivable", "PARAMETER, tighten-only").
 * [NonWaivableFloorChecker] (INV-AC-1786) reads these to tell a `CONTROL` that may
 * never offer a looser option ([NON_WAIVABLE]) from one that may, with a recorded
 * acknowledgement (plain [VpClass.CONTROL], no modifier), from one that is
 * non-waivable only in part ([NON_WAIVABLE_IN_PART], e.g. VP-08: the two-person
 * control is fixed, the duration is not).
 */
enum class VpClassModifier {
    NON_WAIVABLE,
    NON_WAIVABLE_IN_PART,
    TIGHTEN_ONLY,
}

/**
 * INV-REQ-1251 (FR-AI.13) behaviour #1's fixed status vocabulary. Only `AVAILABLE`
 * offers every option; `PLANNED` behaves as its default; `RETIRED` is never offered
 * (behaviours #2-4).
 */
enum class VpStatus {
    AVAILABLE,
    PLANNED,
    RETIRED,
}

/**
 * One parsed row of `variation-points.yaml` (catalogue/variation-points/, vendored
 * from the Keel INV variation-point requirements — see that file's own header and
 * STK-61). [statusNote] and [formDependence] are free text: unlike [status] itself
 * (INV-REQ-1251's own closed vocabulary), the live Keel text does not reduce these to
 * a fixed shape, so nothing here reduces them further — only splits them out from the
 * field they travel with in the source (INV-REQ-1239 behaviour #1).
 */
data class ParsedVariationPoint(
    val id: String,
    val requirement: String,
    val revision: String,
    val title: String,
    val vpClass: VpClass,
    val classModifiers: List<VpClassModifier>,
    val scope: List<String>,
    val owner: String,
    val setBy: List<String>,
    val mustBeElected: Boolean,
    val default: String?,
    val options: List<String>,
    val bounds: String?,
    val status: VpStatus,
    val statusNote: String?,
    val governs: List<String>,
    val formDependence: String?,
    /** Keel's `io_contract.catalogue` (INV-T-3000); null while Keel has none for this row. */
    val fields: VpCatalogueFields? = null,
    /**
     * The open questions named for this row in pending-fields.yaml (INV-T-3000): the
     * only allowed reason for [fields] to be null on a non-retired row. Never a value.
     */
    val pendingQuestions: List<String> = emptyList(),
)

/** INV-REQ-1242 behaviour #1's closed set of scope levels (I, T, G, E, C, F, A). */
enum class ScopeLevel {
    INSTALLATION,
    TENANT,
    GROUP,
    ENTITY,
    FEED,
    AP_CONNECTION,
    CLIENT_ENTITY,
}

/**
 * An option's strictness, judged against the catalogue default (INV-Q-2936): STRICTER
 * is free; LOOSER needs `acknowledgeControlRelaxation` by the accountable role
 * (INV-REQ-1244) and exists only on a plain CONTROL; NEUTRAL is neither (every POLICY
 * alternative, G1/G2 option A). Never inferred from catalogue order.
 */
enum class Strictness {
    STRICTER,
    DEFAULT,
    LOOSER,
    NEUTRAL,
}

/** Which way a typed value tightens (INV-Q-2934). */
enum class Direction {
    UP_STRICTER,
    DOWN_STRICTER,
    NONE,
}

/**
 * The kinds of typed value Keel uses (INV-Q-2934; records are G4 option A). An unknown
 * kind fails the parse, so a new kind in Keel needs a generator change, not a guess.
 */
enum class ValueType(
    val keelName: String,
) {
    INT("int"),
    DURATION("duration"),
    DATE("date"),
    TEXT("text"),
    RECORD("record"),
    LIST_REF("list_ref"),
    SET("set"),
    REF("ref"),
    ;

    companion object {
        fun fromKeel(name: String): ValueType? = entries.find { it.keelName == name }
    }
}

/**
 * A bound or default as Keel states it: a number, or a named value resolved elsewhere
 * (e.g. VP-51's "the mandatory date"). Exactly one of the two is set.
 */
data class BoundValue(
    val number: java.math.BigDecimal? = null,
    val symbolic: String? = null,
)

/** A typed value with its bounds (INV-Q-2934). Fields not stated in Keel stay null. */
data class TypedValue(
    val type: ValueType,
    val unit: String? = null,
    val min: BoundValue? = null,
    val max: BoundValue? = null,
    val default: BoundValue? = null,
    val direction: Direction? = null,
    val ref: String? = null,
    val filter: String? = null,
    val ordered: Boolean? = null,
    val requires: String? = null,
    val fields: Map<String, TypedValue> = emptyMap(),
)

/** One option of a row, at its catalogue position: [key] is the owning enum's constant (TPL-10). */
data class VpOptionSpec(
    val key: String,
    val strictness: Strictness,
    val typedValue: TypedValue? = null,
)

/** Keel's `io_contract.catalogue` for one row (INV-T-3000; INV-Q-2934, INV-Q-2936; G1-G6 option A). */
data class VpCatalogueFields(
    val scopeLevels: List<ScopeLevel>,
    val options: List<VpOptionSpec>,
    val mustBeElected: Boolean? = null,
    val multiSelect: Boolean = false,
    val exclusiveKeys: List<String> = emptyList(),
    val crossScope: String? = null,
    val crossOption: String? = null,
    val crossRow: String? = null,
    val note: String? = null,
    val basis: String? = null,
)
