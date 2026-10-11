package gst.catalogue

import java.math.BigDecimal

/**
 * A profile's status as Keel's `io_contract.profile.status` states it (INV-REQ-1249: a
 * profile is available only with its certification set; INV-Q-3235). An unknown status
 * fails the parse.
 */
enum class ProfileStatus {
    AVAILABLE,
    PLANNED,
}

/** A typed value a profile sets within its option's bounds (e.g. PRF-7 VP-43: 7 years). */
data class ProfileTypedValue(
    val type: ValueType,
    val unit: String?,
    val value: BigDecimal,
)

/**
 * A value Keel carries as `typed: false` (INV-Q-3285 A″): the text states it but no
 * option fits. [pendingQuestion] comes from pending-values.yaml; nothing supplies an option.
 */
data class UnresolvedValue(
    val reason: String,
    val pendingQuestion: String,
)

/**
 * One value of a profile: the variation point [vp] it sets and the option key(s) of that
 * row (INV-Q-3233, INV-Q-3285 A). [option] is set on a single-select row, [options] on a
 * multi-select one; both are empty only when [unresolved] is set. The other fields restate
 * the row's own text: [condition] the applier answers at apply, with [otherwiseOption]
 * when it does not hold; [alternatives] chosen by [alternativeBy]; [adds] list entries;
 * [atApply] the datum the applier supplies; [explicitDefault] where the text names the
 * default; [needsAcknowledgement] the LOOSER options that need acknowledgeControlRelaxation
 * (INV-REQ-1244). [text] is the row's own words and is not part of the value (INV-Q-3283).
 */
data class ParsedProfileValue(
    val vp: String,
    val requirement: String,
    val text: String,
    val option: String? = null,
    val options: List<String> = emptyList(),
    val typedValue: ProfileTypedValue? = null,
    val condition: String? = null,
    val recommended: Boolean = false,
    val otherwiseOption: String? = null,
    val alternatives: List<String> = emptyList(),
    val alternativeBy: String? = null,
    val adds: List<String> = emptyList(),
    val atApply: String? = null,
    val explicitDefault: Boolean = false,
    val needsAcknowledgement: List<String> = emptyList(),
    val unresolved: UnresolvedValue? = null,
) {
    /** Every option key this value references, for the reference check. */
    val referencedKeys: List<String>
        get() = listOfNotNull(option, otherwiseOption) + options + alternatives + needsAcknowledgement

    /** The value without its prose: what a version change is judged on (INV-Q-3283 A′). */
    fun comparable(): ParsedProfileValue = copy(text = "", unresolved = unresolved?.let { UnresolvedValue("", "") })
}

/** One vendored PRF row (INV-REQ-1252..1258) with its Keel profile block. */
data class ParsedOperatingProfile(
    val id: String,
    val requirement: String,
    val revision: String,
    val name: String,
    val title: String,
    val version: Int,
    val status: ProfileStatus,
    val note: String?,
    val values: List<ParsedProfileValue>,
)

/** How one variation point's profile value changed between two profile versions. */
enum class ProfileChangeKind {
    ADDED,
    REMOVED,
    CHANGED,
}

/** One per-profile difference (INV-REQ-1245 b4). */
data class ProfileValueChange(
    val vp: String,
    val kind: ProfileChangeKind,
    val before: ParsedProfileValue?,
    val after: ParsedProfileValue?,
)
