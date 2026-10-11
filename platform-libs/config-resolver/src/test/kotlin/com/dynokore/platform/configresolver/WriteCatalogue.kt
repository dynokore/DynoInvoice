package com.dynokore.platform.configresolver

import java.math.BigDecimal

/**
 * Fixture rows for the write path (INV-Q-3134: acknowledgers come from the catalogue port; the real content
 * arrives with INV-T-3148, so tests use fixtures). Shaped on the gst-catalogue 1.2.0 rows they are named after.
 */
object WriteCatalogue {
    /**
     * VP-62-like: entity and client entity; the looser option needs tax.approver, and client.signatory under
     * delegation.
     */
    val DUE_DATE = VariationPointKey("VP-62")

    /** VP-20-like despatch timing: entity only, a POLICY (INV-AC-2227, INV-AC-2229). */
    val DESPATCH = VariationPointKey("VP-20")

    /** A CONTROL at tenant and group, looser option acknowledged by tenant.admin. */
    val TENANT_CONTROL = VariationPointKey("VP-07")

    /** VP-75-like non-waivable CONTROL whose catalogue still lists a looser option: never offered (INV-AC-2230). */
    val NON_WAIVABLE = VariationPointKey("VP-05")

    /** A looser option with no acknowledger (refused, INV-Q-3134). */
    val NO_ACKNOWLEDGER = VariationPointKey("VP-59")

    /** A looser option acknowledged only under delegation. */
    val DELEGATION_ONLY = VariationPointKey("VP-13")

    /** A looser option whose by-form acknowledger names the private form only. */
    val PRIVATE_ONLY_ACK = VariationPointKey("VP-61")

    /** A looser option whose acknowledger must not be the setter. */
    val DISTINCT_ACK = VariationPointKey("VP-40")

    /** A tenant PARAMETER with a bounded whole number (INV-AC-2224). */
    val BOUNDED = VariationPointKey("VP-42")

    /** A record value (VP-76-like). */
    val RECORD = VariationPointKey("VP-76")

    /** A value with a named bound, checked by the owning service (VP-51-like). */
    val NAMED_BOUND = VariationPointKey("VP-51")

    /** VP-78-like installation CONTROL set by the platform roles (INV-AC-2234), with a looser option. */
    val PLATFORM_MAXIMUM = VariationPointKey("VP-78")

    /** A POLICY allowed at every level, to set at each (INV-AC-2227's positive half, INV-REQ-1242). */
    val EVERY_LEVEL = VariationPointKey("VP-12")

    /**
     * VP-08 as gst-catalogue 1.6.0 states it: installation and tenant; a record whose `window` (1 to 8 hours,
     * default 4, DOWN_STRICTER) is directed and `pair` is not (INV-Q-3204, INV-Q-3320).
     */
    val CROSS_SCOPE = VariationPointKey("VP-08")

    /** A second VP-08-like row, so a test can set its own installation value (INV-Q-3320). */
    val DEFAULT_PAIR = VariationPointKey("VP-86")

    /** VP-67-like: a tenant window up to the installation row [RETRY_MAX] (INV-Q-3202), no direction. */
    val INSTALLATION_BOUND = VariationPointKey("VP-67")

    /** VP-80-like installation maximum whose default option has a fixed typed value. */
    val RETRY_MAX = VariationPointKey("VP-80")

    /** VP-57-like: a tenant window up to [GRACE_MAX], as published (DOWN_STRICTER). */
    val GRACE = VariationPointKey("VP-57")

    /** VP-79-like installation maximum: its default DAYS_14 is a typed value fixed at 14 days (INV-Q-3223). */
    val GRACE_MAX = VariationPointKey("VP-79")

    /** A bounded row whose maximum [UNTYPED_MAX] defaults to an option with no number: refused (fail closed). */
    val UNTYPED_BOUNDED = VariationPointKey("VP-83")

    /** A cross-scope row at tenant and entity, whose tenant value may come from an operating profile. */
    val CROSS_ENTITY = VariationPointKey("VP-85")

    /** An installation maximum whose default option carries no typed value. */
    val UNTYPED_MAX = VariationPointKey("VP-84")

    /** An installation bound declared without the row holding it: refused (INV-Q-3173). */
    val UNNAMED_BOUND = VariationPointKey("VP-31")

    /** A cross-scope rule over options with no directed field: refused. */
    val UNDIRECTED_CROSS = VariationPointKey("VP-09")
    val PLANNED = VariationPointKey("VP-66")
    val RETIRED = VariationPointKey("VP-27")

    private fun def(
        key: VariationPointKey,
        levels: Set<ScopeLevel>,
        options: List<OptionSpec>,
        vpClass: VpClass = VpClass.CONTROL,
        setBy: Set<String> = setOf("tax.approver"),
        modifiers: Set<VpModifier> = emptySet(),
        status: VpStatus = VpStatus.AVAILABLE,
        crossOption: String? = null,
        crossScope: String? = null,
        installationBound: Boolean = false,
        installationParameter: VariationPointKey? = null,
    ) = VariationPointDefinition(
        key = key,
        vpClass = vpClass,
        modifiers = modifiers,
        status = status,
        scopeLevels = levels,
        setBy = setBy,
        options = options,
        crossOption = crossOption,
        crossScope = crossScope,
        installationBound = installationBound,
        installationParameter = installationParameter,
    )

    private fun record(vararg fields: Pair<String, TypedValueSpec>): TypedValueSpec {
        val named = mapOf(*fields)
        return TypedValueSpec(ValueType.RECORD, fields = named)
    }

    private fun hours(
        min: Long,
        max: Long,
        direction: Direction = Direction.DOWN_STRICTER,
    ) = TypedValueSpec(ValueType.DURATION, "hours", min = Bound.of(min), max = Bound.of(max), direction = direction)

    private fun looser(
        key: String,
        vararg acknowledgers: Acknowledger,
    ) = OptionSpec(key, Strictness.LOOSER, acknowledgers = acknowledgers.toList())

    private val int1to4 =
        TypedValueSpec(
            ValueType.INT,
            unit = "periods",
            min = Bound.of(1),
            max = Bound.of(4),
            direction = Direction.UP_STRICTER,
        )

    private val oneDay = Bound.of(1)
    private val days14 = TypedValueSpec(ValueType.DURATION, "days", Bound.of(14), Bound.of(14))

    val rows: Map<VariationPointKey, VariationPointDefinition> =
        listOf(
            def(
                DUE_DATE,
                setOf(ScopeLevel.ENTITY, ScopeLevel.CLIENT_ENTITY),
                listOf(
                    OptionSpec("NO_MOVE", Strictness.DEFAULT),
                    looser(
                        "NEXT_WORKING_DAY",
                        Acknowledger("tax.approver"),
                        Acknowledger("client.signatory", AcknowledgerCondition.UNDER_DELEGATION),
                    ),
                ),
            ),
            def(
                DESPATCH,
                setOf(ScopeLevel.ENTITY),
                listOf(OptionSpec("CONTINUOUS", Strictness.DEFAULT), OptionSpec("DAILY_WINDOW", Strictness.NEUTRAL)),
                vpClass = VpClass.POLICY,
            ),
            def(
                TENANT_CONTROL,
                setOf(ScopeLevel.TENANT, ScopeLevel.GROUP),
                listOf(
                    OptionSpec("SECURITY_OFFICER", Strictness.DEFAULT),
                    looser("TENANT_ADMIN_SOLE", Acknowledger("tenant.admin")),
                ),
                setBy = setOf("tenant.admin"),
            ),
            def(
                NON_WAIVABLE,
                setOf(ScopeLevel.ENTITY),
                listOf(
                    OptionSpec("SECOND_CONFIRMATION", Strictness.DEFAULT),
                    looser("SOLE_OPERATOR", Acknowledger("tenant.admin")),
                ),
                modifiers = setOf(VpModifier.NON_WAIVABLE),
                setBy = setOf("tenant.admin"),
            ),
            def(
                NO_ACKNOWLEDGER,
                setOf(ScopeLevel.ENTITY),
                listOf(OptionSpec("RELEASE", Strictness.DEFAULT), OptionSpec("HOLD", Strictness.LOOSER)),
            ),
            def(
                DELEGATION_ONLY,
                setOf(ScopeLevel.ENTITY, ScopeLevel.CLIENT_ENTITY),
                listOf(
                    OptionSpec("EARLIER_DATE", Strictness.DEFAULT),
                    looser("FILER_OF_RECORD", Acknowledger("client.signatory", AcknowledgerCondition.UNDER_DELEGATION)),
                ),
                setBy = setOf("tax.approver", "client.signatory"),
            ),
            def(
                PRIVATE_ONLY_ACK,
                setOf(ScopeLevel.ENTITY),
                listOf(
                    OptionSpec("ALL_HERE", Strictness.DEFAULT),
                    looser("ELSEWHERE", Acknowledger(roleByForm = mapOf(DeploymentForm.PRIVATE to "tax.approver"))),
                ),
            ),
            def(
                DISTINCT_ACK,
                setOf(ScopeLevel.ENTITY),
                listOf(
                    OptionSpec("PREPARER_AND_APPROVER", Strictness.DEFAULT),
                    looser("SINGLE_ATTESTATION", Acknowledger("tax.approver", distinctFromSetter = true)),
                ),
            ),
            def(
                BOUNDED,
                setOf(ScopeLevel.TENANT),
                listOf(
                    OptionSpec("LAST_PERIOD", Strictness.DEFAULT),
                    OptionSpec("LAST_N", Strictness.STRICTER, true, int1to4),
                ),
                vpClass = VpClass.PARAMETER,
                setBy = setOf("security.officer"),
            ),
            def(
                RECORD,
                setOf(ScopeLevel.ENTITY),
                listOf(
                    OptionSpec("TWO_DP", Strictness.DEFAULT),
                    OptionSpec(
                        "CUSTOM",
                        Strictness.NEUTRAL,
                        true,
                        TypedValueSpec(
                            ValueType.RECORD,
                            fields =
                            mapOf(
                                "scale" to TypedValueSpec(ValueType.INT, min = Bound.of(0), max = Bound.of(4)),
                                "mode" to TypedValueSpec(ValueType.ENUM, values = listOf("HALF_UP", "HALF_EVEN")),
                            ),
                        ),
                    ),
                ),
                vpClass = VpClass.PARAMETER,
            ),
            def(
                NAMED_BOUND,
                setOf(ScopeLevel.ENTITY),
                listOf(
                    OptionSpec("MANDATORY_DATE", Strictness.DEFAULT),
                    OptionSpec(
                        "EARLIER_DATE",
                        Strictness.NEUTRAL,
                        true,
                        TypedValueSpec(ValueType.DATE, max = Bound.named("the mandatory date")),
                    ),
                ),
                vpClass = VpClass.POLICY,
                setBy = setOf("entity.admin"),
            ),
            def(
                PLATFORM_MAXIMUM,
                setOf(ScopeLevel.INSTALLATION),
                listOf(
                    OptionSpec("DEFAULT_LIMITS", Strictness.DEFAULT),
                    OptionSpec(
                        "SMALLER",
                        Strictness.STRICTER,
                        true,
                        TypedValueSpec(
                            ValueType.INT,
                            unit = "bytes",
                            min = Bound.of(1),
                            max = Bound(BigDecimal("52428800")),
                            direction = Direction.DOWN_STRICTER,
                        ),
                    ),
                    looser("LARGER", Acknowledger("security.officer")),
                ),
                setBy = setOf("platform.engineer", "deployment.operator"),
            ),
            def(
                EVERY_LEVEL,
                ScopeLevel.entries.toSet(),
                listOf(OptionSpec("A", Strictness.DEFAULT), OptionSpec("B", Strictness.NEUTRAL)),
                vpClass = VpClass.POLICY,
                setBy = setOf("tenant.admin", "platform.engineer", "deployment.operator"),
            ),
            breakGlass(CROSS_SCOPE),
            breakGlass(DEFAULT_PAIR),
            def(
                INSTALLATION_BOUND,
                setOf(ScopeLevel.TENANT),
                listOf(
                    OptionSpec("H72", Strictness.DEFAULT),
                    OptionSpec(
                        "CUSTOM",
                        Strictness.NEUTRAL,
                        true,
                        TypedValueSpec(ValueType.DURATION, "hours", min = Bound.of(1), max = Bound.row(RETRY_MAX)),
                    ),
                ),
                vpClass = VpClass.PARAMETER,
                setBy = setOf("integration.engineer"),
                installationBound = true,
                installationParameter = RETRY_MAX,
            ),
            def(
                RETRY_MAX,
                setOf(ScopeLevel.INSTALLATION),
                listOf(
                    OptionSpec("HOURS_168", Strictness.DEFAULT, true, hours(168, 168, Direction.NONE)),
                    OptionSpec("CUSTOM", Strictness.NEUTRAL, true, hours(72, 720, Direction.NONE)),
                ),
                vpClass = VpClass.PARAMETER,
                setBy = setOf("platform.engineer", "deployment.operator"),
            ),
            def(
                GRACE,
                setOf(ScopeLevel.TENANT),
                listOf(
                    OptionSpec("PLATFORM_MAXIMUM", Strictness.DEFAULT),
                    OptionSpec(
                        "SHORTER",
                        Strictness.STRICTER,
                        true,
                        TypedValueSpec(
                            ValueType.DURATION,
                            "days",
                            min = Bound.of(1),
                            max = Bound.row(GRACE_MAX),
                            direction = Direction.DOWN_STRICTER,
                        ),
                    ),
                ),
                vpClass = VpClass.PARAMETER,
                modifiers = setOf(VpModifier.TIGHTEN_ONLY),
                installationBound = true,
                installationParameter = GRACE_MAX,
            ),
            def(
                GRACE_MAX,
                setOf(ScopeLevel.INSTALLATION),
                listOf(
                    OptionSpec("DAYS_14", Strictness.DEFAULT, true, days14),
                    OptionSpec("CUSTOM", Strictness.NEUTRAL, true, TypedValueSpec(ValueType.DURATION, "days", oneDay)),
                ),
                vpClass = VpClass.PARAMETER,
                setBy = setOf("platform.engineer", "deployment.operator"),
            ),
            def(
                UNTYPED_BOUNDED,
                setOf(ScopeLevel.TENANT),
                listOf(
                    OptionSpec("PLATFORM_MAXIMUM", Strictness.DEFAULT),
                    OptionSpec(
                        "SHORTER",
                        Strictness.STRICTER,
                        true,
                        TypedValueSpec(
                            ValueType.DURATION,
                            "days",
                            min = Bound.of(1),
                            max = Bound.row(UNTYPED_MAX),
                            direction = Direction.DOWN_STRICTER,
                        ),
                    ),
                ),
                vpClass = VpClass.PARAMETER,
                modifiers = setOf(VpModifier.TIGHTEN_ONLY),
                installationBound = true,
                installationParameter = UNTYPED_MAX,
            ),
            def(
                UNTYPED_MAX,
                setOf(ScopeLevel.INSTALLATION),
                listOf(
                    OptionSpec("DAYS_14", Strictness.DEFAULT),
                    OptionSpec("CUSTOM", Strictness.NEUTRAL, true, TypedValueSpec(ValueType.DURATION, "days", oneDay)),
                ),
                vpClass = VpClass.PARAMETER,
                setBy = setOf("platform.engineer", "deployment.operator"),
            ),
            def(
                CROSS_ENTITY,
                setOf(ScopeLevel.TENANT, ScopeLevel.ENTITY),
                listOf(
                    OptionSpec("H4", Strictness.DEFAULT, true, hours(4, 4)),
                    OptionSpec("CUSTOM", Strictness.NEUTRAL, true, hours(1, 8)),
                ),
                vpClass = VpClass.PARAMETER,
                crossScope = "An entity value may not exceed the tenant value",
            ),
            def(
                UNNAMED_BOUND,
                setOf(ScopeLevel.TENANT),
                listOf(OptionSpec("H72", Strictness.DEFAULT), OptionSpec("LONGER", Strictness.NEUTRAL)),
                vpClass = VpClass.PARAMETER,
                setBy = setOf("integration.engineer"),
                installationBound = true,
            ),
            def(
                UNDIRECTED_CROSS,
                setOf(ScopeLevel.INSTALLATION, ScopeLevel.TENANT),
                listOf(OptionSpec("PAIR_4H", Strictness.DEFAULT), OptionSpec("OTHER", Strictness.NEUTRAL)),
                setBy = setOf("security.officer"),
                crossScope = "A tenant value may not exceed the installation value",
            ),
            def(
                PLANNED,
                setOf(ScopeLevel.ENTITY),
                listOf(OptionSpec("DAYS_90", Strictness.DEFAULT), OptionSpec("LONGER", Strictness.STRICTER)),
                status = VpStatus.PLANNED,
            ),
            def(RETIRED, setOf(ScopeLevel.ENTITY), emptyList(), status = VpStatus.RETIRED),
        ).associateBy { it.key }

    val catalogue =
        object : VariationPointCatalogue {
            override fun find(key: VariationPointKey) = rows[key]

            override fun keys() = rows.keys
        }
}
