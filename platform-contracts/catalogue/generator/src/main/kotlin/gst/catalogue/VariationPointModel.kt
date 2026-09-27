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
)
