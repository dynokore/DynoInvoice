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

    val CROSS_SCOPE = VariationPointKey("VP-08")
    val INSTALLATION_BOUND = VariationPointKey("VP-67")
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
    )

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
            def(
                CROSS_SCOPE,
                setOf(ScopeLevel.INSTALLATION, ScopeLevel.TENANT),
                listOf(OptionSpec("PAIR_4H", Strictness.DEFAULT), OptionSpec("OTHER", Strictness.NEUTRAL)),
                setBy = setOf("security.officer"),
                crossScope = "A tenant value may not exceed the installation value",
            ),
            def(
                INSTALLATION_BOUND,
                setOf(ScopeLevel.TENANT),
                listOf(OptionSpec("H72", Strictness.DEFAULT), OptionSpec("LONGER", Strictness.NEUTRAL)),
                vpClass = VpClass.PARAMETER,
                setBy = setOf("integration.engineer"),
                installationBound = true,
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
