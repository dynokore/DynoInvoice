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

/**
 * A typed value a profile sets within its option's bounds (e.g. PRF-7 VP-43: 7 years). A
 * scalar carries [value]; a record (e.g. PRF-7 VP-08: DEFAULT_PAIR_4H with a 2 h window,
 * INV-Q-3320) carries no value of its own, only the [fields] it sets, each a typed value.
 */
data class ProfileTypedValue(
    val type: ValueType,
    val unit: String?,
    val value: BigDecimal?,
    val fields: Map<String, ProfileTypedValue> = emptyMap(),
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
 * [conditionFact] types [condition] as the FACT record whose fact decides it (INV-T-3636,
 * INV-Q-3692): newly added it restates the condition and is not a change ([sameValueAs]).
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
    val conditionFact: ConditionFact? = null,
) {
    /** Every option key this value references, for the reference check. */
    val referencedKeys: List<String>
        get() = listOfNotNull(option, otherwiseOption) + options + alternatives + needsAcknowledgement

    /** The value without its prose: what a version change is judged on (INV-Q-3283 A′). */
    fun comparable(): ParsedProfileValue = copy(text = "", unresolved = unresolved?.let { UnresolvedValue("", "") })

    /**
     * True when [newer] holds the same value as this (older) one. A condition_fact newly added to a
     * value that had none only types its existing condition and is not a change (INV-Q-3692); once
     * a value has one, changing or removing it is a change like any other.
     */
    fun sameValueAs(newer: ParsedProfileValue): Boolean =
        comparable() == newer.comparable().let { if (conditionFact == null) it.copy(conditionFact = null) else it }
}

/** The FACT record [constraint] of the value's row whose [fact] decides its condition (INV-Q-3612, INV-Q-3692). */
data class ConditionFact(
    val constraint: String,
    val fact: ConstraintFact,
)

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
