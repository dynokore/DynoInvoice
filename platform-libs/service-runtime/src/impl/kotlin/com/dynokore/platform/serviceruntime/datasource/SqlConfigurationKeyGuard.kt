package com.dynokore.platform.serviceruntime.datasource

import com.dynokore.platform.tenancykernel.SqlIdentifier
import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.boot.context.properties.source.ConfigurationPropertyName
import org.springframework.boot.context.properties.source.ConfigurationPropertySource
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.context.properties.source.IterableConfigurationPropertySource
import org.springframework.core.Ordered
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Coordinator ruling INV-Q-4542 (INV-ADR-3968 item 4c, INV-REQ-1230, INV-REQ-854 b8, INV-T-3982): no Spring
 * configuration key may run SQL or set session state. Raw SQL lives only in reviewed `.sql` migrations; the tenant and
 * actor settings are set only by the transaction runner; role timeouts are set on the role by provisioning, never by
 * connection-init SQL.
 *
 * Registered in `META-INF/spring.factories`, so it runs before any bean is created in every service and in the
 * migration runner ([com.dynokore.platform.serviceruntime.migration.MigrationRunner]), after the application's own
 * configuration is loaded. It reads every property source through [ConfigurationPropertySources] (properties, YAML,
 * environment variables, command-line arguments, Helm values, installation-profile values), compares names in their
 * relaxed (uniform) form, and fails start-up naming every offending key and the ruling. It is deny-by-default (lessons
 * P140): under `spring.datasource` and `spring.flyway` only the allowlisted keys pass. Values are never printed. The
 * password properties are refused with or without a credential property, as is a URL `password` parameter (STK-29,
 * coordinator ruling INV-Q-4548).
 *
 * A key is added to an allowlist only through a Keel ruling.
 */
class SqlConfigurationKeyGuard :
    EnvironmentPostProcessor,
    Ordered {
    // after ConfigDataEnvironmentPostProcessor: application.yaml, profiles and config imports are loaded
    override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE

    override fun postProcessEnvironment(
        environment: ConfigurableEnvironment,
        application: SpringApplication,
    ) {
        val offending = findOffendingKeys(environment)
        check(offending.isEmpty()) {
            "Spring configuration keys that run SQL or set session state are refused (coordinator ruling INV-Q-4542; " +
                "INV-ADR-3968 item 4c, INV-REQ-1230): remove $offending"
        }
    }

    companion object {
        /** Every refused key, sorted, with the reason; each as its property source names it. */
        fun findOffendingKeys(environment: ConfigurableEnvironment): List<String> = ConfigurationPropertySources
            .get(environment)
            .filterIsInstance<IterableConfigurationPropertySource>()
            .flatMap { source ->
                val env = source.underlyingSource is SystemEnvironmentPropertySource
                source.mapNotNull { name ->
                    refusal(name, env) { valueOf(source, name, environment) }?.let { reason ->
                        val origin = source.getConfigurationProperty(name)?.origin?.let { " from $it" }.orEmpty()
                        "$name ($reason$origin)"
                    }
                }
            }.distinct()
            .sorted()

        /**
         * The reason [name] is refused, or null when it is allowed. [environmentVariable] relaxes word boundaries as an
         * environment variable does (`SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE`); [value] is the resolved value.
         */
        fun refusal(
            name: ConfigurationPropertyName,
            environmentVariable: Boolean = false,
            value: () -> String? = { null },
        ): String? {
            val elements = (0 until name.numberOfElements).map { name.getElement(it, ConfigurationPropertyName.Form.UNIFORM) }
            REFUSED_PREFIXES.firstOrNull { it.isAncestorOf(name) || it == name }?.let { return "under $it" }
            sqlSegment(elements, environmentVariable)?.let { return "runs SQL: $it" }
            if (DATASOURCE.isAncestorOf(name) || DATASOURCE == name) {
                return allowlisted(elements.drop(2), DATASOURCE_ALLOWED, environmentVariable, value)
            }
            if (FLYWAY.isAncestorOf(name) || FLYWAY == name) {
                return allowlisted(elements.drop(2), FLYWAY_ALLOWED, environmentVariable, value)
            }
            if (name == EVENT_LOG_URL) return urlRefusal(value())
            return null
        }

        private fun allowlisted(
            rest: List<String>,
            allowed: Map<String, (String?) -> String?>,
            environmentVariable: Boolean,
            value: () -> String?,
        ): String? {
            // a list value (`locations[0]`) is checked as its list key
            val key = rest.dropLastWhile { it.isNotEmpty() && it.all(Char::isDigit) }
            val joined = key.joinToString(".")
            val check =
                allowed[joined]
                    ?: (if (environmentVariable) allowed.entries.firstOrNull { it.key.replace(".", "") == key.joinToString("") }?.value else null)
                    ?: (if (key.size >= 2 && key.first() == PLACEHOLDERS) allowed["$PLACEHOLDERS.*"] else null)
                    ?: return "not on the allowlist"
            return check(value())
        }

        private fun sqlSegment(
            elements: List<String>,
            environmentVariable: Boolean,
        ): String? {
            val last = elements.lastOrNull() ?: return null
            SQL_SEGMENTS.firstOrNull { last.contains(it) }?.let { return it }
            if (!environmentVariable) return null
            // FOO_INIT_SQL binds to foo.init-sql: a pattern spanning the last segments, ending in the last one
            val joined = elements.joinToString("")
            val lastStart = joined.length - last.length
            return SQL_SEGMENTS.firstOrNull { pattern ->
                generateSequence(joined.indexOf(pattern)) { at -> joined.indexOf(pattern, at + 1).takeIf { it >= 0 } }
                    .takeWhile { it >= 0 }
                    .any { at -> at + pattern.length > lastStart }
            }
        }

        private fun valueOf(
            source: ConfigurationPropertySource,
            name: ConfigurationPropertyName,
            environment: ConfigurableEnvironment,
        ): String? = source.getConfigurationProperty(name)?.value?.toString()?.let(environment::resolvePlaceholders)

        private fun urlRefusal(url: String?): String? {
            val query = url?.substringAfter('?', "") ?: return null
            val parameters = query.split('&').map { URLDecoder.decode(it.substringBefore('='), StandardCharsets.UTF_8).trim() }
            return when {
                parameters.any { it.equals("options", ignoreCase = true) } -> "the pgjdbc options parameter sets server settings"

                // STK-29: credentials never in configuration (coordinator ruling INV-Q-4548)
                parameters.any { it.equals("password", ignoreCase = true) } -> "a password in the URL (STK-29, INV-Q-4548)"

                else -> null
            }
        }

        private fun only(
            expected: String,
            ignoreCase: Boolean = false,
        ): (String?) -> String? = { value ->
            if (value == null || value.trim().equals(expected, ignoreCase)) null else "only $expected"
        }

        private val ANY: (String?) -> String? = { null }

        private val CLASSPATH_LOCATIONS: (String?) -> String? = { value ->
            val locations = value.orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
            if (locations.all { it.startsWith("classpath:") }) null else "only classpath: locations (CS-19)"
        }

        private val IDENTIFIER: (String?) -> String? = { value ->
            if (value == null || runCatching { SqlIdentifier.require(value, "placeholder") }.isSuccess) {
                null
            } else {
                "a placeholder value must be a plain identifier (SqlIdentifier, INV-Q-4036)"
            }
        }

        private const val PLACEHOLDERS = "placeholders"
        private val DATASOURCE = ConfigurationPropertyName.of("spring.datasource")
        private val FLYWAY = ConfigurationPropertyName.of("spring.flyway")
        private val EVENT_LOG_URL = ConfigurationPropertyName.of("gst.db.event-log-url")

        // ---- The allowlists and refusals of coordinator ruling INV-Q-4542; changed only through a Keel ruling ----

        /** (1) Every key under these prefixes is refused: SQL scripts, and banned JPA, Hibernate and R2DBC. */
        val REFUSED_PREFIXES: List<ConfigurationPropertyName> =
            listOf(
                "spring.sql",
                "spring.jpa",
                "spring.r2dbc",
                "spring.batch",
                "spring.quartz",
                "spring.session.jdbc",
                "spring.integration.jdbc",
                "spring.modulith.events.jdbc",
            ).map(ConfigurationPropertyName::of)

        /** (2) Under any prefix, a key whose last segment contains one of these (uniform form) is refused. */
        val SQL_SEGMENTS: List<String> =
            listOf("initsql", "initializeschema", "schemainitialization", "schemalocations", "datalocations")

        /** (3) The keys allowed under `spring.datasource` (uniform form), with their value check. */
        val DATASOURCE_ALLOWED: Map<String, (String?) -> String?> =
            mapOf(
                "url" to { value -> urlRefusal(value) },
                "username" to ANY,
                "driverclassname" to ANY,
                "type" to only("com.zaxxer.hikari.HikariDataSource"),
                "hikari.maximumpoolsize" to ANY,
                "hikari.minimumidle" to ANY,
                "hikari.connectiontimeout" to ANY,
                "hikari.idletimeout" to ANY,
                "hikari.maxlifetime" to ANY,
                "hikari.keepalivetime" to ANY,
                "hikari.validationtimeout" to ANY,
                "hikari.leakdetectionthreshold" to ANY,
                "hikari.initializationfailtimeout" to ANY,
                "hikari.poolname" to ANY,
                "hikari.registermbeans" to ANY,
            )

        /** (4) The keys allowed under `spring.flyway` (uniform form), with their value check. */
        val FLYWAY_ALLOWED: Map<String, (String?) -> String?> =
            mapOf(
                "enabled" to ANY,
                "url" to { value -> urlRefusal(value) },
                "user" to ANY,
                "schemas" to ANY,
                "defaultschema" to ANY,
                "createschemas" to ANY,
                "locations" to CLASSPATH_LOCATIONS,
                "table" to ANY,
                "baselineonmigrate" to ANY,
                "baselineversion" to ANY,
                "baselinedescription" to ANY,
                "validateonmigrate" to ANY,
                "outoforder" to ANY,
                "cleandisabled" to only("true", ignoreCase = true),
                "connectretries" to ANY,
                "connectretriesinterval" to ANY,
                "lockretrycount" to ANY,
                "failonmissinglocations" to ANY,
                "postgresql.transactionallock" to ANY,
                "placeholderreplacement" to ANY,
                "$PLACEHOLDERS.*" to IDENTIFIER,
            )
    }
}
