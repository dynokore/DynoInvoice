package com.dynokore.platform.configresolver

/**
 * VP-08 as gst-catalogue 1.6.0 states it (INV-REQ-1269): installation and tenant; DEFAULT_PAIR_4H and
 * OTHER_NAMED_PAIR are records whose `window` is 1 to 8 hours, default 4, DOWN_STRICTER, and whose `pair` is
 * never compared (INV-Q-3204, INV-Q-3206, INV-Q-3320).
 */
internal fun breakGlass(key: VariationPointKey): VariationPointDefinition {
    val window =
        TypedValueSpec(
            ValueType.DURATION,
            "hours",
            min = Bound.of(1),
            max = Bound.of(8),
            direction = Direction.DOWN_STRICTER,
            default = Bound.of(4),
        )
    val pair = TypedValueSpec(ValueType.LIST_REF, ref = "pairs")
    return VariationPointDefinition(
        key = key,
        vpClass = VpClass.CONTROL,
        modifiers = setOf(VpModifier.NON_WAIVABLE_IN_PART),
        status = VpStatus.AVAILABLE,
        scopeLevels = setOf(ScopeLevel.INSTALLATION, ScopeLevel.TENANT),
        setBy = setOf("security.officer"),
        options =
        listOf(
            OptionSpec(
                "DEFAULT_PAIR_4H",
                Strictness.DEFAULT,
                true,
                TypedValueSpec(ValueType.RECORD, fields = mapOf("window" to window)),
            ),
            OptionSpec(
                "OTHER_NAMED_PAIR",
                Strictness.NEUTRAL,
                true,
                TypedValueSpec(ValueType.RECORD, fields = mapOf("pair" to pair, "window" to window)),
            ),
        ),
        crossScope = "A tenant value may not exceed the installation value",
    )
}
