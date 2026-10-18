package com.dynokore.platform.configresolver

import java.math.BigDecimal

/**
 * The installation-wide maximum of an option's numeric value (INV-T-6538; INV-Q-6529 A, INV-Q-6527 A): the largest
 * numeric member of the value stored for [maximum]'s option over every tenant and every scope of the data plane. It is
 * a number only, never a tenant, a scope or a row. Read in a platform transaction through the sys-owned function
 * [ConfigSchema.OPTION_VALUE_MAXIMUM]; a transaction of a tenant is refused.
 */
interface OptionValueMaximum {
    /**
     * The largest numeric member of the value stored for [option] of [key]: the number itself when the value is a
     * number, else every member of the value object that is a number (a record's fields). A value counts when it is
     * the one in force today for its (tenant, scope level, scope id) or has an `effective_from` after today and is not
     * superseded by a later-recorded row of the same scope and date; of one scope's rows those of source OVERRIDE count
     * when the scope has any, else those of source PROFILE. `null` when no stored value of [option] has a numeric
     * member.
     */
    fun maximum(
        key: VariationPointKey,
        option: String,
    ): BigDecimal?
}
