package com.dynokore.platform.tenancyjooq

import org.jooq.Configuration
import org.jooq.SQLDialect
import org.jooq.conf.Settings

/**
 * The jOOQ settings tenancy-jooq owns (INV-T-4133; review of !61), checked when a configuration is configured and
 * again on every statement, since a configuration's [Settings] object can still change afterwards. Deny by default:
 * apart from [CONSUMER_SETTINGS] (execution limits and logging, none of which renders SQL), every setting must keep
 * jOOQ's default, so no `renderMapping` (mapped schemata or tables), `renderSchema`, `renderCatalog`,
 * `renderTable`, name quoting or case, parameter or statement type, inlining, transformation, parser,
 * interpreter or listener-order setting can change a table's identity or how the tenant predicate renders. The
 * dialect is PostgreSQL's.
 */
internal object OwnedSettings {
    private val DEFAULT = Settings()

    /** The settings a consumer's configuration may change, each copied from the defaults before comparing. */
    private val CONSUMER_SETTINGS: List<(Settings, Settings) -> Unit> =
        listOf(
            { to, from -> to.queryTimeout = from.queryTimeout },
            { to, from -> to.maxRows = from.maxRows },
            { to, from -> to.largeMaxRows = from.largeMaxRows },
            { to, from -> to.fetchSize = from.fetchSize },
            { to, from -> to.batchSize = from.batchSize },
            { to, from -> to.queryPoolable = from.queryPoolable },
            { to, from -> to.setExecuteLogging(from.isExecuteLogging) },
            { to, from -> to.setExecuteLoggingSQLExceptions(from.isExecuteLoggingSQLExceptions) },
            { to, from -> to.setFetchWarnings(from.isFetchWarnings) },
        )

    /** The names of [CONSUMER_SETTINGS], for the refusal's message. */
    private const val CONSUMER_SETTING_NAMES =
        "queryTimeout, maxRows, largeMaxRows, fetchSize, batchSize, queryPoolable, executeLogging, " +
            "executeLoggingSQLExceptions, fetchWarnings"

    /**
     * jOOQ fills an empty render mapping and empty lists into a configuration's settings on first use; empty, they
     * are the defaults, so they are compared as unset.
     */
    private fun normalise(settings: Settings) {
        val mapping = settings.renderMapping
        if (mapping != null &&
            mapping.defaultCatalog == null &&
            mapping.defaultSchema == null &&
            mapping.catalogs.isEmpty() &&
            mapping.schemata.isEmpty()
        ) {
            settings.renderMapping = null
        }
        if (settings.interpreterSearchPath.isEmpty()) settings.interpreterSearchPath = null
        if (settings.migrationSchemata.isEmpty()) settings.migrationSchemata = null
        if (settings.parseSearchPath.isEmpty()) settings.parseSearchPath = null
    }

    fun require(configuration: Configuration) {
        if (configuration.dialect() != SQLDialect.POSTGRES) {
            throw TenantPredicateViolation(
                "tenancy-jooq runs on the POSTGRES dialect only, not ${configuration.dialect()}",
            )
        }
        val owned = configuration.settings().clone() as Settings
        CONSUMER_SETTINGS.forEach { copy -> copy(owned, DEFAULT) }
        normalise(owned)
        if (owned != DEFAULT) {
            throw TenantPredicateViolation(
                "a jOOQ setting tenancy-jooq owns differs from jOOQ's default (only $CONSUMER_SETTING_NAMES may be " +
                    "set): no render mapping, schema, catalog, name, parameter, transformation or other setting " +
                    "may change how a statement or its tenant predicate renders",
            )
        }
    }
}
