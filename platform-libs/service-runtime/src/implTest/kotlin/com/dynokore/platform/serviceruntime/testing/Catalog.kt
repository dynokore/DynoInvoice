package com.dynokore.platform.serviceruntime.testing

import com.dynokore.platform.tenancyjooq.TableClass
import org.jooq.Field
import org.jooq.Table
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType

/**
 * The PostgreSQL catalog tables the integration tests read through jOOQ (they hold no tenant data: PLATFORM_SCOPED), and
 * the fields they use. A test declares [TABLES] to its [TestDatabase] and reads under the platform binding.
 */
val PG_CLASS: Table<*> = DSL.table(DSL.name("pg_catalog", "pg_class"))
val PG_CLASS_NAME: Field<String> = DSL.field(DSL.name("pg_class", "relname"), SQLDataType.VARCHAR)
val PG_CLASS_FORCE: Field<Boolean> = DSL.field(DSL.name("pg_class", "relforcerowsecurity"), SQLDataType.BOOLEAN)
val PG_CLASS_OID: Field<Long> = DSL.field(DSL.name("pg_class", "oid"), SQLDataType.BIGINT)
val PG_CLASS_OWNER: Field<Long> = DSL.field(DSL.name("pg_class", "relowner"), SQLDataType.BIGINT)
val PG_CLASS_NAMESPACE: Field<Long> = DSL.field(DSL.name("pg_class", "relnamespace"), SQLDataType.BIGINT)

val PG_PROC: Table<*> = DSL.table(DSL.name("pg_catalog", "pg_proc"))
val PG_PROC_NAME: Field<String> = DSL.field(DSL.name("pg_proc", "proname"), SQLDataType.VARCHAR)
val PG_PROC_NAMESPACE: Field<Long> = DSL.field(DSL.name("pg_proc", "pronamespace"), SQLDataType.BIGINT)
val PG_PROC_OWNER: Field<Long> = DSL.field(DSL.name("pg_proc", "proowner"), SQLDataType.BIGINT)
val PG_PROC_DEFINER: Field<Boolean> = DSL.field(DSL.name("pg_proc", "prosecdef"), SQLDataType.BOOLEAN)
val PG_PROC_CONFIG: Field<Array<String>> =
    DSL.field(DSL.name("pg_proc", "proconfig"), SQLDataType.CLOB.array())
val PG_PROC_OID: Field<Long> = DSL.field(DSL.name("pg_proc", "oid"), SQLDataType.BIGINT)

val PG_NAMESPACE: Table<*> = DSL.table(DSL.name("pg_catalog", "pg_namespace"))
val PG_NAMESPACE_NAME: Field<String> = DSL.field(DSL.name("pg_namespace", "nspname"), SQLDataType.VARCHAR)
val PG_NAMESPACE_OID: Field<Long> = DSL.field(DSL.name("pg_namespace", "oid"), SQLDataType.BIGINT)

/** The catalog tables above, for [TestDatabase]. */
val CATALOG_TABLES: Map<String, TableClass> =
    listOf("pg_catalog.pg_class", "pg_catalog.pg_proc", "pg_catalog.pg_namespace")
        .associateWith { TableClass.PLATFORM_SCOPED }
