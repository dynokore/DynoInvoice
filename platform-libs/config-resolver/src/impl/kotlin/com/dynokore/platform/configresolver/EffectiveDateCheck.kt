package com.dynokore.platform.configresolver

import java.time.Clock
import java.time.LocalDate

/** The effective-date rule of [JdbcPolicyWriter]. */
internal class EffectiveDateCheck(
    private val writes: JdbcSettingWrites,
    private val backdating: BackdatingFloor?,
    private val clock: Clock,
) {
    /** Never retroactive, except a scope's first value back to its floor (INV-Q-2940, INV-Q-2977, INV-Q-3140). */
    fun check(
        definition: VariationPointDefinition,
        target: SettingTarget,
        effectiveFrom: LocalDate,
    ) {
        val today = BusinessDate.today(clock)
        if (effectiveFrom >= today) return
        val key = definition.key
        if (target.level == ScopeLevel.TENANT || target.level == ScopeLevel.INSTALLATION) {
            refuse(key, PolicyRefusal.BACKDATING_REFUSED, "a ${target.level} value takes effect $today or later")
        }
        if (writes.hasAny(key, target)) {
            refuse(key, PolicyRefusal.BACKDATING_REFUSED, "only a scope's first value may be back-dated")
        }
        val floor =
            backdating?.let { port -> runCatching { port.earliest(target, key) }.getOrNull() }
                ?: refuse(key, PolicyRefusal.BACKDATING_REFUSED, "no back-dating floor for ${target.scopeText}")
        if (effectiveFrom < floor) {
            refuse(key, PolicyRefusal.BACKDATING_REFUSED, "the earliest allowed date is $floor")
        }
    }
}
