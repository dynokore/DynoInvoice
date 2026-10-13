package com.dynokore.platform.configresolver

/** A small catalogue in the shape of gst-catalogue 1.2.0 rows. */
object TestCatalogue {
    val ENTITY_POLICY = VariationPointKey("VP-11")
    val ALL_LEVELS = VariationPointKey("VP-12")
    val PLANNED = VariationPointKey("VP-13")
    val RETIRED = VariationPointKey("VP-27")
    val MUST_ELECT = VariationPointKey("VP-22")
    val BY_FORM = VariationPointKey("VP-74")
    val INSTALLATION_ONLY = VariationPointKey("VP-72")
    val MULTI = VariationPointKey("VP-46")
    val TYPED = VariationPointKey("VP-17")
    val UNKNOWN = VariationPointKey("VP-99")

    /** Options listed loosest first, with the default in the middle: strictness comes from the field, not the order. */
    private val looseFirst =
        listOf(
            OptionSpec("SOLE_OPERATOR", Strictness.LOOSER),
            OptionSpec("TWO_PERSON", Strictness.DEFAULT),
            OptionSpec("FOUR_EYES_PLUS", Strictness.STRICTER),
        )

    private fun row(
        key: VariationPointKey,
        levels: Set<ScopeLevel>,
        status: VpStatus = VpStatus.AVAILABLE,
        options: List<OptionSpec> = looseFirst,
        vpClass: VpClass = VpClass.CONTROL,
        setBy: Set<String> = setOf("tax.approver"),
        defaultByForm: Map<DeploymentForm, String> = emptyMap(),
        mustBeElected: Boolean = false,
        multiSelect: Boolean = false,
    ) = VariationPointDefinition(
        key = key,
        vpClass = vpClass,
        modifiers = emptySet(),
        status = status,
        scopeLevels = levels,
        setBy = setBy,
        options = options,
        defaultByForm = defaultByForm,
        mustBeElected = mustBeElected,
        multiSelect = multiSelect,
    )

    val rows: Map<VariationPointKey, VariationPointDefinition> =
        listOf(
            row(ENTITY_POLICY, setOf(ScopeLevel.ENTITY, ScopeLevel.TENANT)),
            row(ALL_LEVELS, ScopeLevel.entries.toSet()),
            row(PLANNED, setOf(ScopeLevel.TENANT), status = VpStatus.PLANNED),
            row(RETIRED, setOf(ScopeLevel.TENANT), status = VpStatus.RETIRED),
            row(
                MUST_ELECT,
                setOf(ScopeLevel.ENTITY),
                options = listOf(OptionSpec("STANDARD", Strictness.NEUTRAL), OptionSpec("CASH", Strictness.NEUTRAL)),
                vpClass = VpClass.POLICY,
                mustBeElected = true,
            ),
            row(
                BY_FORM,
                setOf(ScopeLevel.INSTALLATION),
                options = listOf(OptionSpec("ON", Strictness.NEUTRAL), OptionSpec("OFF", Strictness.NEUTRAL)),
                vpClass = VpClass.POLICY,
                setBy = setOf("platform.engineer", "deployment.operator"),
                defaultByForm = mapOf(DeploymentForm.SAAS to "ON", DeploymentForm.PRIVATE to "OFF"),
            ),
            row(
                INSTALLATION_ONLY,
                setOf(ScopeLevel.INSTALLATION),
                options =
                    listOf(
                        OptionSpec("PLATFORM_DEFAULT", Strictness.DEFAULT),
                        OptionSpec("OPERATOR_MAXIMUM", Strictness.NEUTRAL, typed = true),
                    ),
                vpClass = VpClass.PARAMETER,
                setBy = setOf("platform.engineer", "deployment.operator"),
            ),
            row(
                MULTI,
                setOf(ScopeLevel.TENANT),
                options =
                    listOf(
                        OptionSpec("EMAIL_DEFAULT_ROLES", Strictness.DEFAULT),
                        OptionSpec("SMS", Strictness.NEUTRAL),
                        OptionSpec("WEBHOOK", Strictness.NEUTRAL),
                    ),
                vpClass = VpClass.POLICY,
                multiSelect = true,
            ),
            row(
                TYPED,
                setOf(ScopeLevel.ENTITY),
                options =
                    listOf(
                        OptionSpec("DAYS_30", Strictness.DEFAULT),
                        OptionSpec("OTHER_PERIOD", Strictness.NEUTRAL, typed = true),
                    ),
                vpClass = VpClass.PARAMETER,
            ),
        ).associateBy { it.key }

    val catalogue = VariationPointCatalogue { rows[it] }
}

/** VP-11 / VP-12 options as an owning service would declare them (TPL-10). */
enum class ApprovalRule { SOLE_OPERATOR, TWO_PERSON, FOUR_EYES_PLUS }
