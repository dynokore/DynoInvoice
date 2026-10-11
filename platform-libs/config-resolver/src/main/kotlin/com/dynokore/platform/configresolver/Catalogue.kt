package com.dynokore.platform.configresolver

/** INV-REQ-1239 behaviour #1's class vocabulary (`LAW` is never a variation point). */
enum class VpClass { POLICY, CONTROL, PARAMETER }

/** Modifiers on [VpClass] (INV-REQ-780 behaviour #1; gst-catalogue `VpClassModifier`). */
enum class VpModifier { TIGHTEN_ONLY, NON_WAIVABLE, NON_WAIVABLE_IN_PART }

/**
 * INV-REQ-1251 behaviour #1: only AVAILABLE offers its options; PLANNED behaves as its default; RETIRED is
 * never offered.
 */
enum class VpStatus { AVAILABLE, PLANNED, RETIRED }

/**
 * An option's strictness, judged against the catalogue default (ruling INV-Q-2936; gst-catalogue 1.2.0
 * `Strictness`). It is an explicit per-option catalogue field: nothing in this module reads strictness
 * from the order the options are listed in.
 */
enum class Strictness { STRICTER, DEFAULT, LOOSER, NEUTRAL }

/** The two deployment forms (INV-REQ-1248). The catalogue is the same in both; only a by-form default differs. */
enum class DeploymentForm { SAAS, PRIVATE }

/** A variation point identifier such as `VP-20`; the only way to name one (ARC-12). */
@JvmInline
value class VariationPointKey(
    val value: String,
) {
    init {
        require(PATTERN.matches(value)) { "a variation point key looks like VP-20, was '$value'" }
    }

    override fun toString(): String = value

    private companion object {
        val PATTERN = Regex("^VP-\\d{2,3}$")
    }
}

/**
 * One option at its catalogue position (gst-catalogue `VpOption`).
 *
 * @property key the owning service's enum constant (TPL-10).
 * @property typed whether the option carries a typed value (a number, duration, list ...; INV-Q-2934).
 */
data class OptionSpec(
    val key: String,
    val strictness: Strictness,
    val typed: Boolean = false,
) {
    init {
        require(KEY.matches(key)) { "an option key is an enum constant name, was '$key'" }
    }

    private companion object {
        val KEY = Regex("^[A-Z][A-Z0-9_]*$")
    }
}

/**
 * One registered variation point, as the gst-catalogue 1.2.0 `VariationPoint` states it (STK-61, CS-28;
 * INV-Q-2934). The catalogue is the only source of what may be resolved; this module holds no list of its
 * own. A service adapts the generated `VariationPoint` to this shape in its composition root.
 *
 * @property scopeLevels the levels the row allows (INV-REQ-1242 behaviour #1).
 * @property setBy the roles that may set it.
 * @property options the options in catalogue order, each with its own [Strictness].
 * @property defaultByForm the default per deployment form, for a row whose default depends on it (e.g. VP-74).
 * @property mustBeElected the row has no default: nothing set means [ElectionRequired] (INV-Q-2930).
 * @property multiSelect a value may hold several option keys.
 * @property exclusiveKeys keys that, when chosen, must be the only key.
 * @property formDependence why an option depends on the deployment form, when it does (INV-REQ-1248 behaviour #2).
 */
data class VariationPointDefinition(
    val key: VariationPointKey,
    val vpClass: VpClass,
    val modifiers: Set<VpModifier>,
    val status: VpStatus,
    val scopeLevels: Set<ScopeLevel>,
    val setBy: Set<String>,
    val options: List<OptionSpec>,
    val defaultByForm: Map<DeploymentForm, String> = emptyMap(),
    val mustBeElected: Boolean = false,
    val multiSelect: Boolean = false,
    val exclusiveKeys: Set<String> = emptySet(),
    val formDependence: String? = null,
) {
    private val byKey: Map<String, OptionSpec> = options.associateBy { it.key }

    init {
        require(status == VpStatus.RETIRED || options.isNotEmpty()) { "$key has no options" }
        require(byKey.size == options.size) { "$key option keys must be distinct" }
        require(status == VpStatus.RETIRED || scopeLevels.isNotEmpty()) { "$key allows no scope" }
        val defaults = options.filter { it.strictness == Strictness.DEFAULT }.map { it.key }
        require(defaultByForm.values.all { it in byKey }) { "$key by-form default is not one of its options" }
        require(exclusiveKeys.all { it in byKey }) { "$key exclusive key is not one of its options" }
        if (status != VpStatus.RETIRED) {
            when {
                mustBeElected ->
                    require(defaults.isEmpty() && defaultByForm.isEmpty()) { "$key must be elected and has a default" }

                defaultByForm.isNotEmpty() -> require(defaultByForm.keys == DeploymentForm.entries.toSet()) {
                    "$key by-form default must name every form"
                }

                else -> require(defaults.size == 1 || (multiSelect && defaults.isNotEmpty())) {
                    "$key needs exactly one DEFAULT option, or must be elected"
                }
            }
        }
    }

    fun option(key: String): OptionSpec? = byKey[key]

    /**
     * The default value in [form]: the options marked [Strictness.DEFAULT] (never the first listed), or the
     * row's by-form default; `null` when the row must be elected.
     */
    fun defaultKeys(form: DeploymentForm): List<String>? = when {
        mustBeElected -> null
        defaultByForm.isNotEmpty() -> listOf(defaultByForm.getValue(form))
        else -> options.filter { it.strictness == Strictness.DEFAULT }.map { it.key }.ifEmpty { null }
    }

    /**
     * The options offered (INV-REQ-1251): all when available, only the default when planned, none when
     * retired. Both forms offer the same available options (INV-REQ-1248 behaviour #1); the row's
     * [formDependence] states why one cannot be used in a form.
     */
    fun offeredOptions(form: DeploymentForm): List<String> = when (status) {
        VpStatus.AVAILABLE -> options.map { it.key }
        VpStatus.PLANNED -> defaultKeys(form).orEmpty()
        VpStatus.RETIRED -> emptyList()
    }
}

/** The registered variation points. An implementation adapts the generated gst-catalogue `VariationPoint`. */
fun interface VariationPointCatalogue {
    /** `null` when [key] is not registered: such a key is refused, never defaulted. */
    fun find(key: VariationPointKey): VariationPointDefinition?

    fun require(key: VariationPointKey): VariationPointDefinition = find(key) ?: throw UnknownVariationPoint(key)
}
