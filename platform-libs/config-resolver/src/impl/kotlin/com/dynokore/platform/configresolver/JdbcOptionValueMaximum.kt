package com.dynokore.platform.configresolver

import com.dynokore.platform.tenancykernel.SqlIdentifier
import org.jooq.DSLContext
import org.jooq.Name
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.math.BigDecimal

/**
 * [OptionValueMaximum] through the sys-owned function [ConfigSchema.OPTION_VALUE_MAXIMUM], called as a jOOQ function
 * call on the DSLContext of tenancy-jooq's factory, in a transaction bound to the platform tenant. The function refuses
 * any other binding (SQLSTATE 42501) and returns one number, so this class sees no tenant, scope or row.
 */
class JdbcOptionValueMaximum(
    private val dsl: DSLContext,
    schema: String,
) : OptionValueMaximum {
    // INV-REQ-854 b5 (coordinator ruling INV-Q-4036): the schema enters a name only through SqlIdentifier.require.
    private val function: Name = DSL.name(SqlIdentifier.require(schema, "schema"), ConfigSchema.OPTION_VALUE_MAXIMUM)

    override fun maximum(
        key: VariationPointKey,
        option: String,
    ): BigDecimal? =
        dsl
            .select(DSL.function(function, SQLDataType.NUMERIC, DSL.`val`(key.value), DSL.`val`(option)))
            .fetchOne { it.value1() }
}
