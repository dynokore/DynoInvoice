package com.dynokore.platform.serviceruntime.datasource

import com.dynokore.platform.tenancykernel.SqlIdentifier
import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.boot.context.properties.source.ConfigurationPropertyName
import org.springframework.boot.context.properties.source.ConfigurationPropertySource
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.context.properties.source.IterableConfigurationPropertySource
import org.springframework.context.EnvironmentAware
import org.springframework.core.Ordered
import org.springframework.core.PriorityOrdered
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.Environment
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.jmx.export.MBeanExporter
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
        val loaders =
            listOfNotNull(
                application.classLoader,
                Thread.currentThread().contextClassLoader,
                SqlConfigurationKeyGuard::class.java.classLoader,
            )
        verify(environment) { name -> loaders.any { it.getResource(name) != null } }
    }

    companion object {
        /**
         * Fails naming every refused key of [environment] and every external pool or driver configuration file
         * visible to the class path, asked through [resourceExists] (INV-Q-4559). Run by this post-processor and again
         * by [SqlConfigurationKeyRecheck].
         */
        fun verify(
            environment: ConfigurableEnvironment,
            resourceExists: (String) -> Boolean,
        ) {
            val offending =
                findOffendingKeys(environment) + findPasswordProperties(environment) +
                    findExternalConfiguration(resourceExists)
            check(offending.isEmpty()) {
                "Spring configuration keys that run SQL, set session state, remove the start-up guards or load " +
                    "classes or bean definitions are refused (coordinator rulings INV-Q-4542, INV-Q-4559, " +
                    "INV-Q-4570, INV-Q-4591, INV-Q-4603; INV-ADR-3968 item 4c, INV-REQ-1230): remove $offending"
            }
        }

        /**
         * (6) The external pool and driver configuration files (coordinator ruling INV-Q-4559): the system property
         * `hikaricp.configurationFile`, read by every `new HikariConfig()`, and pgjdbc's class-path
         * `org/postgresql/driverconfig.properties` default connection properties. Either can set connectionInitSql,
         * schema and options.
         */
        fun findExternalConfiguration(
            resourceExists: (String) -> Boolean,
            systemProperty: (String) -> String? = System::getProperty,
        ): List<String> =
            listOfNotNull(
                systemProperty(HIKARI_CONFIGURATION_FILE)?.let {
                    "system property $HIKARI_CONFIGURATION_FILE (an external pool configuration file)"
                },
                DRIVER_CONFIGURATION
                    .takeIf(resourceExists)
                    ?.let { "class-path resource $DRIVER_CONFIGURATION (pgjdbc default connection properties)" },
            )

        /**
         * The password keys, read by exact name through [Environment.getProperty], which also consults property
         * sources that cannot be enumerated (coordinator ruling INV-Q-4570; STK-29, INV-Q-4548). A value that cannot be
         * resolved counts as set.
         */
        fun findPasswordProperties(environment: Environment): List<String> =
            PASSWORD_KEYS
                .filter { key -> runCatching { environment.getProperty(key) != null }.getOrDefault(true) }
                .map { "$it (a password: the login comes only through SecretStore (STK-29, INV-Q-4570))" }

        /** Every refused key, sorted, with the reason; each as its property source names it. */
        fun findOffendingKeys(environment: ConfigurableEnvironment): List<String> =
            ConfigurationPropertySources
                .get(environment)
                // a source that cannot be enumerated (a stub, a servlet-context source before the web server) is not
                // listed here;
                // PoolConfigurationGuard checks what such a source binds into a pool
                .filterIsInstance<IterableConfigurationPropertySource>()
                .flatMap { source ->
                    val env = source.underlyingSource is SystemEnvironmentPropertySource
                    source.mapNotNull { name ->
                        refusal(name, env) { valueOf(source, name, environment) }?.let { reason ->
                            val origin =
                                source
                                    .getConfigurationProperty(name)
                                    ?.origin
                                    ?.let { " from $it" }
                                    .orEmpty()
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
        ): String? = SqlKeyRefusals.refusal(name, environmentVariable, value)

        private fun valueOf(
            source: ConfigurationPropertySource,
            name: ConfigurationPropertyName,
            environment: ConfigurableEnvironment,
        ): String? =
            source
                .getConfigurationProperty(name)
                ?.value
                ?.toString()
                ?.let(environment::resolvePlaceholders)

        /**
         * (5) The parameters of a JDBC URL not on [URL_PARAMETERS_ALLOWED] (coordinator ruling INV-Q-4559), named
         * exactly as pgjdbc reads them after URL decoding; values are never printed.
         */
        internal fun urlRefusal(url: String?): String? {
            val query = url?.substringAfter('?', "") ?: return null
            val refused =
                query
                    .split('&')
                    .filter(String::isNotEmpty)
                    .map { decode(it.substringBefore('=')) to decode(it.substringAfter('=', "")) }
                    .filterNot { (name, value) -> URL_PARAMETERS_ALLOWED[name]?.invoke(value) == true }
                    .map { it.first }
            return when {
                refused.isEmpty() -> null

                // STK-29: credentials never in configuration (coordinator ruling INV-Q-4548)
                refused.any { it.equals("password", ignoreCase = true) } -> "a password in the URL (STK-29, INV-Q-4548)"

                else -> "URL parameters not on the allowlist (INV-Q-4559): ${refused.sorted()}"
            }
        }

        private fun decode(text: String): String = URLDecoder.decode(text, StandardCharsets.UTF_8)

        private fun only(
            expected: String,
            ignoreCase: Boolean = false,
        ): (String?) -> String? =
            { value ->
                if (value == null || value.trim().equals(expected, ignoreCase)) null else "only $expected"
            }

        private val ANY: (String?) -> String? = { null }

        private val CLASSPATH_LOCATIONS: (String?) -> String? = { value ->
            val locations =
                value
                    .orEmpty()
                    .split(',')
                    .map(String::trim)
                    .filter(String::isNotEmpty)
            if (locations.all { it.startsWith("classpath:") }) null else "only classpath: locations (CS-19)"
        }

        private val IDENTIFIER: (String?) -> String? = { value ->
            if (value == null || runCatching { SqlIdentifier.require(value, "placeholder") }.isSuccess) {
                null
            } else {
                "a placeholder value must be a plain identifier (SqlIdentifier, INV-Q-4036)"
            }
        }

        private val PASSWORD_KEYS =
            listOf("spring.datasource.password", "spring.datasource.hikari.password", "spring.flyway.password")
        private const val PLATFORM_PACKAGE = "com.dynokore.platform."
        private const val POSTGRESQL_DRIVER = "org.postgresql.Driver"
        private const val HIKARI_CONFIGURATION_FILE = "hikaricp.configurationFile"
        private const val DRIVER_CONFIGURATION = "org/postgresql/driverconfig.properties"

        // ---- The allowlists and refusals of coordinator ruling INV-Q-4542; changed only through a Keel ruling ----

        /**
         * (8) The keys that remove the start-up guards or load classes or bean definitions by name (coordinator ruling
         * INV-Q-4603; uniform form, matched with list indexes dropped and, as an environment variable, with word
         * boundaries relaxed), with their value check.
         */
        val BOOTSTRAP_KEYS: Map<List<String>, (String?) -> String?> =
            mapOf(
                // an excluded service-runtime auto-configuration removes the pool check
                listOf("spring", "autoconfigure", "exclude") to { value ->
                    val platform =
                        value
                            .orEmpty()
                            .split(',')
                            .map(String::trim)
                            .any { it.startsWith(PLATFORM_PACKAGE) }
                    if (platform) {
                        "excludes a $PLATFORM_PACKAGE class, which removes a start-up guard (INV-Q-4603)"
                    } else {
                        null
                    }
                },
                // an overriding bean definition could replace a guard
                listOf("spring", "main", "allowbeandefinitionoverriding") to { value ->
                    only("false", ignoreCase = true)(value)?.let { "$it: a bean could replace a guard (INV-Q-4603)" }
                },
                listOf("spring", "main", "sources") to
                    { _ -> "class names or XML locations loaded as bean definitions (INV-Q-4603)" },
                listOf("context", "initializer", "classes") to { _ -> "classes instantiated by name (INV-Q-4603)" },
                listOf("context", "listener", "classes") to { _ -> "classes instantiated by name (INV-Q-4603)" },
                // the Spring application admin MBean is JMX (INV-Q-4591)
                listOf("spring", "application", "admin", "enabled") to { value ->
                    only("false", ignoreCase = true)(value)?.let { "$it: JMX is refused (INV-Q-4591, INV-Q-4603)" }
                },
            )

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

        /** (5) The parameters a JDBC URL may carry (coordinator ruling INV-Q-4559), with their value check. */
        val URL_PARAMETERS_ALLOWED: Map<String, (String) -> Boolean> =
            mapOf(
                // Testcontainers' jdbcUrl adds it; OFF only
                "loggerLevel" to { value -> value == "OFF" },
                // TLS to PostgreSQL (INV-T-2893): transport only, no session state, no class loading
                "sslmode" to { _ -> true },
                "sslrootcert" to { _ -> true },
            )

        /** (3) The keys allowed under `spring.datasource` (uniform form), with their value check. */
        val DATASOURCE_ALLOWED: Map<String, (String?) -> String?> =
            mapOf(
                "url" to { value -> urlRefusal(value) },
                "username" to ANY,
                // the PostgreSQL driver only (coordinator ruling INV-Q-4603)
                "driverclassname" to only(POSTGRESQL_DRIVER),
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
                "${SqlKeyRefusals.PLACEHOLDERS}.*" to IDENTIFIER,
            )
    }
}

/**
 * The second run of [SqlConfigurationKeyGuard] (coordinator ruling INV-Q-4559): after configuration-class parsing and
 * before any application bean, so a property source added after environment post-processing (a `@PropertySource` on
 * any configuration class, an ApplicationContextInitializer, an earlier post-processor) is checked too.
 *
 * It also fails start-up, before any bean is created, on a bean definition of type [MBeanExporter] or a subclass
 * (Spring
 * Boot's JMX auto-configuration, `@EnableMBeanExport`, an exporter `@Bean`, a library's exporter): an exporter exports
 * every HikariDataSource as HikariConfigMXBean, with a writable Password (coordinator ruling INV-Q-4591). INV uses no
 * JMX: metrics, health and tracing are HTTP on the management port (STK-33, STK-34, INV-T-4125). INV-T-4593 grants
 * this class the MBeanExporter name.
 */
class SqlConfigurationKeyRecheck :
    BeanFactoryPostProcessor,
    EnvironmentAware,
    PriorityOrdered {
    private lateinit var environment: ConfigurableEnvironment

    override fun setEnvironment(environment: Environment) {
        this.environment = environment as ConfigurableEnvironment
    }

    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE

    override fun postProcessBeanFactory(beanFactory: ConfigurableListableBeanFactory) {
        // the application's class loaders, as before: the bean class loader, the context's and this library's
        val loaders =
            listOfNotNull(
                beanFactory.beanClassLoader,
                Thread.currentThread().contextClassLoader,
                SqlConfigurationKeyGuard::class.java.classLoader,
            )
        SqlConfigurationKeyGuard.verify(environment) { name -> loaders.any { it.getResource(name) != null } }
        val exporters = findMBeanExporters(beanFactory)
        check(exporters.isEmpty()) {
            "JMX export is refused: an MBeanExporter exports every HikariDataSource with a writable Password " +
                "(coordinator " +
                "ruling INV-Q-4591; STK-29): remove the MBeanExporter beans $exporters"
        }
    }

    companion object {
        /** The beans of type [MBeanExporter] or a subclass, by definition (no bean is created), sorted. */
        fun findMBeanExporters(beanFactory: ConfigurableListableBeanFactory): List<String> =
            beanFactory.getBeanNamesForType(MBeanExporter::class.java, true, false).sorted()
    }
}
