package com.dynokore.platform.serviceruntime.composition

import org.jooq.Record
import org.jooq.Schema
import org.jooq.Table
import org.jooq.TableField
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.jooq.impl.SchemaImpl
import org.jooq.impl.TableImpl
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.UUID

/**
 * A service's generated jOOQ schema in the shape jOOQ's code generator emits (a SchemaImpl and TableImpl subclasses),
 * for a TPL-style service fixture (INV-T-4134): [spec] names each table and whether it has a `tenant_id` column.
 */
class FixtureSchema(
    name: String,
    private val spec: List<Pair<String, Boolean>>,
) : SchemaImpl(DSL.name(name), null) {
    private val built: List<FixtureTable> by lazy { spec.map { (table, tenant) -> FixtureTable(table, this, tenant) } }

    override fun getTables(): List<Table<*>> = built

    fun table(name: String): FixtureTable = built.first { it.name == name }
}

class FixtureTable(
    name: String,
    schema: Schema,
    tenant: Boolean,
) : TableImpl<Record>(DSL.name(name), schema) {
    val id: TableField<Record, UUID> = createField(DSL.name("id"), SQLDataType.UUID, this)
    val tenantId: TableField<Record, UUID>? =
        if (tenant) {
            createField(
                DSL.name("tenant_id"),
                SQLDataType.UUID,
                this,
            )
        } else {
            null
        }
    val label: TableField<Record, String> = createField(DSL.name("label"), SQLDataType.CLOB, this)

    companion object {
        fun id(n: Int): UUID = UUID(0L, n.toLong())
    }
}

/** The service fixture's own schemas: `item` is TENANT, `flag` is declared PLATFORM_SCOPED by property. */
object Tables {
    const val SCHEMA = "submission"

    /** The library tables, tenantless and declared by their own modules, and the service's two. */
    val LIBRARY =
        listOf(
            "config_installation_setting" to false,
            "consumer_offset" to false,
            "consumer_replay" to false,
            "consumer_replay_marker" to false,
            "consumer_source_seen" to false,
            "lane_rate_window" to false,
            "shedlock" to false,
            "job_retention" to false,
            "outbox" to true,
        )
    val SERVICE = listOf("item" to true, "flag" to false)
}

/** The service's `org.jooq.Schema` bean (what its jOOQ code generator emits as a constant). */
@Configuration(proxyBeanMethods = false)
class SubmissionSchemaConfiguration {
    @Bean
    fun submissionSchema(): FixtureSchema = FixtureSchema(Tables.SCHEMA, Tables.SERVICE + Tables.LIBRARY)
}

/** As above, plus a tenantless table nobody declared. */
@Configuration(proxyBeanMethods = false)
class StraySchemaConfiguration {
    @Bean
    fun submissionSchema(): FixtureSchema =
        FixtureSchema(Tables.SCHEMA, Tables.SERVICE + Tables.LIBRARY + listOf("stray" to false))
}

/** Schema supply look-alikes (INV-Q-4815): none of these is an `org.jooq.Schema` that holds a table. */
@Configuration(proxyBeanMethods = false)
class EmptySchemaConfiguration {
    @Bean
    fun emptySchema(): FixtureSchema = FixtureSchema(Tables.SCHEMA, emptyList())
}

@Configuration(proxyBeanMethods = false)
class CatalogOnlyConfiguration {
    @Bean
    fun catalog(): org.jooq.Catalog = org.jooq.impl.CatalogImpl("lookalike")
}

@Configuration(proxyBeanMethods = false)
class TableOnlyConfiguration {
    @Bean
    fun table(): FixtureTable = FixtureSchema(Tables.SCHEMA, Tables.SERVICE).table("item")
}

/**
 * The generated schema every composition fixture of this module supplies (INV-Q-4815: a service with a database
 * declares
 * its `org.jooq.Schema` beans in its bootstrap; TPL-18 compositions here have no real service behind them). Switched
 * off with `test.default-schema=false` by the tests that supply their own.
 */
@Configuration(proxyBeanMethods = false)
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    name = ["test.default-schema"],
    havingValue = "true",
    matchIfMissing = true,
)
class DefaultServiceSchema {
    @Bean
    fun fixtureDefaultSchema(): FixtureSchema = FixtureSchema("fixture_default", listOf("default_item" to true))
}

/** The service schema in which `consumer_replay` gained a `tenant_id` column. */
@Configuration(proxyBeanMethods = false)
class ReplayGainsTenantSchemaConfiguration {
    @Bean
    fun submissionSchema(): FixtureSchema =
        FixtureSchema(
            Tables.SCHEMA,
            Tables.SERVICE + Tables.LIBRARY.map { (name, tenant) -> name to (tenant || name == "consumer_replay") },
        )
}
