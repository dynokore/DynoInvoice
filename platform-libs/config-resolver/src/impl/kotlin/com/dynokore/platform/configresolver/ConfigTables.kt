package com.dynokore.platform.configresolver

import com.dynokore.platform.tenancyjooq.TableClass
import com.dynokore.platform.tenancykernel.SqlIdentifier
import org.jooq.Name
import org.jooq.impl.DSL

/**
 * This module's tables for tenancy-jooq's factory `TenancyJooq.dslContext(tables, tenants, transactionRunner)`, named with
 * `DSL.name(schema, table)` and declared with their class (INV-ADR-3968 item 4a, CS-18). The class follows from the
 * published DDL in [ConfigSchema]: three tables carry `tenant_id` ([TableClass.TENANT]); [ConfigSchema.INSTALLATION_SETTING]
 * has none and carries the table comment `platform-scoped: no tenant data` ([TableClass.PLATFORM_SCOPED]).
 */
object ConfigTables {
    /** The tables of [schema] with their class; the schema enters a name only after [SqlIdentifier.require]. */
    fun declared(schema: String): Map<Name, TableClass> {
        val s = SqlIdentifier.require(schema, "schema")
        return mapOf(
            DSL.name(s, ConfigSchema.SETTING) to TableClass.TENANT,
            DSL.name(s, ConfigSchema.RESOLVED_VALUE) to TableClass.TENANT,
            DSL.name(s, ConfigSchema.RELAXATION_ACKNOWLEDGEMENT) to TableClass.TENANT,
            DSL.name(s, ConfigSchema.INSTALLATION_SETTING) to TableClass.PLATFORM_SCOPED,
        )
    }
}
