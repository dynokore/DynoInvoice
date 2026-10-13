package com.dynokore.platform.serviceruntime.datasource

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.SpringApplication
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.support.ResourcePropertySource
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path

/**
 * Coordinator ruling INV-Q-4542: [SqlConfigurationKeyGuard] refuses, before any bean exists, every Spring configuration
 * key that runs SQL or sets session state, in any relaxed form, and passes service-runtime's defaults and a TPL-16
 * configuration.
 */
class SqlConfigurationKeyGuardTest {
    @ParameterizedTest(name = "{0}={1}")
    @CsvSource(
        delimiter = '|',
        value = [
            "spring.flyway.init-sqls|select 1",
            "spring.datasource.hikari.connection-init-sql|set search_path to x",
            "spring.sql.init.mode|always",
            "spring.sql.init.schema-locations|classpath:schema.sql",
            "spring.jpa.hibernate.ddl-auto|create",
            "spring.jpa.properties.hibernate.hbm2ddl.auto|create",
            "spring.datasource.hikari.data-source-properties.options|-c search_path=x",
            "spring.datasource.hikari.schema|other",
            "spring.datasource.dbcp2.connection-init-sqls|select 1",
            "spring.datasource.tomcat.init-s-q-l|select 1",
            "spring.datasource.type|org.apache.commons.dbcp2.BasicDataSource",
            "spring.flyway.locations|filesystem:/tmp/sql",
            "spring.flyway.placeholders.x|a; drop table t",
            "spring.flyway.clean-disabled|false",
            "spring.batch.jdbc.initialize-schema|always",
            "spring.datasource.hikari.unknown-setting|17",
            "spring.datasource.url|jdbc:postgresql://db:5432/app?options=-c%20search_path%3Dx",
            "spring.flyway.url|jdbc:postgresql://db:5432/app?sslmode=require&OPTIONS=-c%20x%3Dy",
            "gst.db.event-log-url|jdbc:postgresql://log:5432/app?Options=-c%20x%3Dy",
            "spring.datasource.url|jdbc:postgresql://db:5432/app?password=x",
            "spring.flyway.url|jdbc:postgresql://db:5432/app?user=a&PASSWORD=pw-in-url",
            "gst.db.event-log-url|jdbc:postgresql://log:5432/app?Password=pw-in-url",
            "spring.datasource.password|secret-value",
            "spring.datasource.hikari.password|secret-value",
            "spring.flyway.password|secret-value",
            "spring.flyway.callback-locations|classpath:callbacks",
            "spring.flyway.sql-migration-prefix|X",
            "spring.datasource.hikari.catalog|other",
            "spring.datasource.hikari.transaction-isolation|TRANSACTION_SERIALIZABLE",
            "spring.datasource.hikari.auto-commit|false",
            "spring.datasource.hikari.read-only|true",
            "spring.datasource.oracleucp.sql-for-validate-connection|select 1",
            "spring.datasource.initialization-mode|always",
            "spring.datasource.schema|classpath:schema.sql",
            "spring.datasource.data|classpath:data.sql",
            "spring.r2dbc.url|r2dbc:postgresql://db/app",
            "spring.quartz.jdbc.initialize-schema|always",
            "spring.session.jdbc.table-name|sessions_tbl",
            "spring.integration.jdbc.platform|postgresql",
            "spring.modulith.events.jdbc.schema-initialization.enabled|true",
            "gst.audit.schema-locations|classpath:x.sql",
            "service-runtime.anything.data-locations|classpath:x.sql",
            "my.store.initialize-schema|true",
        ],
    )
    fun `start-up fails naming the key and the ruling`(
        key: String,
        value: String,
    ) {
        val failure = shouldThrow<IllegalStateException> { guard(properties(key to value)) }
        failure.message.orEmpty() shouldContain "INV-Q-4542"
        flat(failure.message.orEmpty()) shouldContain flat(key)
        // a refused value (a password, an options string) is never printed (CS-24)
        failure.message.orEmpty() shouldNotContain value
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "spring.flyway.init-sqls", "spring.flyway.initSqls", "spring.flyway.init_sqls", "spring.flyway.INIT-SQLS",
            "spring.datasource.hikari.connection-init-sql", "spring.datasource.hikari.connectionInitSql",
            "spring.datasource.hikari.connection_init_sql", "spring.datasource.hikari.dataSourceProperties.options",
            "spring.datasource.hikari.data-source-properties[options]", "spring.sql.init.schemaLocations",
            "spring.jpa.hibernate.ddlAuto", "spring.flyway.cleanDisabled", "spring.datasource.hikari.connectionInitSQL",
        ],
    )
    fun `the relaxed property forms are refused`(key: String) {
        shouldThrow<IllegalStateException> { guard(properties(key to "false")) }.message.orEmpty() shouldContain "INV-Q-4542"
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "SPRING_FLYWAY_INITSQLS", "SPRING_FLYWAY_INIT_SQLS", "SPRING_DATASOURCE_HIKARI_CONNECTIONINITSQL",
            "SPRING_DATASOURCE_HIKARI_CONNECTION_INIT_SQL", "SPRING_DATASOURCE_HIKARI_SCHEMA", "SPRING_SQL_INIT_MODE",
            "SPRING_JPA_HIBERNATE_DDL_AUTO", "SPRING_DATASOURCE_PASSWORD", "SPRING_FLYWAY_CALLBACK_LOCATIONS",
            "SPRING_DATASOURCE_HIKARI_DATASOURCEPROPERTIES_OPTIONS", "SPRING_BATCH_JDBC_INITIALIZE_SCHEMA",
            "GST_STORE_INIT_SQL", "GST_STORE_SCHEMA_LOCATIONS", "SPRING_FLYWAY_CLEANDISABLED",
        ],
    )
    fun `the environment variable forms are refused`(variable: String) {
        val failure = shouldThrow<IllegalStateException> { guard(environmentVariables(variable to "false")) }
        failure.message.orEmpty() shouldContain "INV-Q-4542"
        flat(failure.message.orEmpty()) shouldContain flat(variable)
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["spring.datasource.password", "spring.datasource.hikari.password", "spring.flyway.password"])
    fun `a password property is refused with no service-runtime credential property set (STK-29, INV-Q-4548)`(key: String) {
        val environment = properties(key to "pw-value")
        environment.getProperty("service-runtime.datasource.credential") shouldBe null
        environment.getProperty("service-runtime.flyway.credential") shouldBe null
        val failure = shouldThrow<IllegalStateException> { guard(environment) }
        failure.message.orEmpty() shouldContain key
        failure.message.orEmpty() shouldNotContain "pw-value"
    }

    @Test
    fun `a password in a JDBC URL is refused, and the URL is not printed (STK-29, INV-Q-4548)`() {
        val failure = shouldThrow<IllegalStateException> { guard(properties("spring.datasource.url" to "jdbc:postgresql://db/app?password=x")) }
        failure.message.orEmpty() shouldContain "spring.datasource.url"
        failure.message.orEmpty() shouldContain "INV-Q-4548"
        failure.message.orEmpty() shouldNotContain "password=x"
    }

    @Test
    fun `every offending key is named in one failure`() {
        val failure =
            shouldThrow<IllegalStateException> {
                guard(properties("spring.flyway.init-sqls" to "select 1", "spring.datasource.hikari.connection-init-sql" to "select 1"))
            }
        failure.message.orEmpty() shouldContain "spring.flyway.init-sqls"
        failure.message.orEmpty() shouldContain "spring.datasource.hikari.connection-init-sql"
    }

    @Test
    fun `service-runtime's defaults plus a TPL-16 configuration pass, in properties and environment variable forms`() {
        val environment = properties(*TPL_16)
        environment.propertySources.addLast(ResourcePropertySource(ClassPathResource("service-runtime-defaults.properties")))
        SqlConfigurationKeyGuard.findOffendingKeys(environment).shouldBeEmpty()
        guard(environment)

        val variables =
            TPL_16.map { (key, value) -> key.uppercase().replace('.', '_').replace("-", "") to value } +
                listOf(
                    "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE" to "7",
                    "SPRING_DATASOURCE_HIKARI_MAX_LIFETIME" to "1800000",
                    "SPRING_FLYWAY_DEFAULT_SCHEMA" to "submission",
                    "SPRING_FLYWAY_POSTGRESQL_TRANSACTIONAL_LOCK" to "false",
                    "SPRING_FLYWAY_LOCATIONS_0" to "classpath:db/migration",
                    "PATH" to "/usr/bin",
                )
        SqlConfigurationKeyGuard.findOffendingKeys(environmentVariables(*variables.toTypedArray())).shouldBeEmpty()
    }

    @Test
    fun `allowed values pass and refused values fail on the allowlisted keys`() {
        guard(
            properties(
                "spring.datasource.type" to "com.zaxxer.hikari.HikariDataSource",
                "spring.flyway.clean-disabled" to "true",
                "spring.flyway.locations[0]" to "classpath:db/migration",
                "spring.flyway.locations[1]" to "classpath:db/callbacks",
                "spring.flyway.placeholders.svc" to "submission",
                "spring.datasource.url" to "jdbc:postgresql://db:5432/app?loggerLevel=OFF&sslmode=verify-full&sslrootcert=/etc/ssl/pg.crt",
            ),
        )
        shouldThrow<IllegalStateException> { guard(properties("spring.flyway.locations[1]" to "filesystem:/sql")) }
        shouldThrow<IllegalStateException> { guard(properties("spring.flyway.locations" to "classpath:a, filesystem:/sql")) }
        shouldThrow<IllegalStateException> { guard(properties("spring.flyway.placeholders.svc" to "Submission")) }
    }

    @Test
    fun `a value resolved from a placeholder is checked`() {
        val environment =
            properties(
                "gst.db.event-log-url" to "\${LOG_URL}",
                "LOG_URL" to "jdbc:postgresql://log:5432/app?options=-c%20x%3Dy",
            )
        shouldThrow<IllegalStateException> { guard(environment) }.message.orEmpty() shouldContain "gst.db.event-log-url"
    }

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource(
        delimiter = '|',
        value = [
            "GST_DB_EVENT_LOG_URL|jdbc:postgresql://log:5432/app?options=-c%20search_path%3Dx",
            "GST_DB_EVENT_LOG_URL|jdbc:postgresql://log:5432/app?password=pw-value",
            "GST_DB_EVENTLOGURL|jdbc:postgresql://log:5432/app?options=-c%20search_path%3Dx",
            "GST_DB_EVENTLOGURL|jdbc:postgresql://log:5432/app?password=pw-value",
        ],
    )
    fun `the event-log URL is checked in its environment variable forms`(
        variable: String,
        url: String,
    ) {
        val environment = environmentVariables(variable to url)
        if (variable == "GST_DB_EVENT_LOG_URL") environment.getProperty("gst.db.event-log-url") shouldBe url
        val failure = shouldThrow<IllegalStateException> { guard(environment) }
        failure.message.orEmpty() shouldContain variable
        failure.message.orEmpty() shouldNotContain url
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "server.servlet.context-parameters.spring.datasource.hikari.schema",
            "server.servlet.context-parameters[spring.datasource.hikari.schema]",
            "server.servlet.contextParameters.spring.datasource.hikari.connectionTestQuery",
            "server.servlet.context-parameters.spring.datasource.hikari.data-source-properties.options",
            "server.servlet.context-parameters.spring.datasource.password",
            "server.servlet.context-parameters.spring.flyway.init-sqls",
            "server.servlet.context-parameters.my.store.init-sql",
        ],
    )
    fun `a refused key as a servlet-context parameter is refused`(key: String) {
        val failure = shouldThrow<IllegalStateException> { guard(properties(key to "evil")) }
        failure.message.orEmpty() shouldContain "INV-Q-4542"
        failure.message.orEmpty() shouldContain "servlet-context parameter"
    }

    @Test
    fun `a refused key as a servlet-context parameter environment variable is refused, an ordinary parameter passes`() {
        shouldThrow<IllegalStateException> {
            guard(environmentVariables("SERVER_SERVLET_CONTEXTPARAMETERS_SPRING_DATASOURCE_HIKARI_SCHEMA" to "evil"))
        }.message.orEmpty() shouldContain "SERVER_SERVLET_CONTEXTPARAMETERS_SPRING_DATASOURCE_HIKARI_SCHEMA"
        guard(properties("server.servlet.context-parameters.display-mode" to "compact"))
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["my.store.init-sqls[0]", "my.store.init-sql[a]", "my.store.initSqls[3]", "my.store.connection-init-sql[0]"])
    fun `an indexed or map-keyed SQL key under any prefix is refused`(key: String) {
        shouldThrow<IllegalStateException> { guard(properties(key to "select 1")) }.message.orEmpty() shouldContain "runs SQL"
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["MY_STORE_INITSQLS_0", "MY_STORE_INIT_SQLS_0", "MY_STORE_CONNECTIONINITSQL_1"])
    fun `an indexed SQL environment variable under any prefix is refused`(variable: String) {
        shouldThrow<IllegalStateException> { guard(environmentVariables(variable to "select 1")) }.message.orEmpty() shouldContain "runs SQL"
    }

    @ParameterizedTest(name = "{0}?{1}")
    @CsvSource(
        delimiter = '|',
        value = [
            "spring.datasource.url|currentSchema=x", "spring.datasource.url|service=x", "spring.datasource.url|ApplicationName=x",
            "spring.datasource.url|readOnly=true", "spring.datasource.url|loggerLevel=TRACE", "spring.datasource.url|sslfactory=x",
            "spring.datasource.url|sslmode=require&readOnlyMode=always", "spring.datasource.url|SSLMODE=require",
            "gst.db.event-log-url|currentSchema=x", "gst.db.event-log-url|service=x", "gst.db.event-log-url|ApplicationName=x",
            "gst.db.event-log-url|readOnly=true", "gst.db.event-log-url|loggerLevel=TRACE", "gst.db.event-log-url|cur%72entSchema=x",
            "spring.flyway.url|currentSchema=x", "spring.flyway.url|loggerLevel=off",
        ],
    )
    fun `a URL parameter not on the allowlist is refused, named without its value (INV-Q-4559)`(
        key: String,
        query: String,
    ) {
        val failure = shouldThrow<IllegalStateException> { guard(properties(key to "jdbc:postgresql://db:5432/app?$query")) }
        failure.message.orEmpty() shouldContain key
        failure.message.orEmpty() shouldContain "INV-Q-4559"
        failure.message.orEmpty() shouldNotContain "=x"
    }

    @Test
    fun `the allowlisted URL parameters pass on every pool URL (INV-Q-4559)`() {
        val url = "jdbc:postgresql://db:5432/app?loggerLevel=OFF&sslmode=verify-full&sslrootcert=/etc/ssl/pg.crt"
        guard(properties("spring.datasource.url" to url, "spring.flyway.url" to url, "gst.db.event-log-url" to url))
        guard(environmentVariables("SPRING_DATASOURCE_URL" to url, "GST_DB_EVENT_LOG_URL" to url))
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["hikaricp.configurationFile", "hikaricp.configuration-file", "HIKARICP_CONFIGURATIONFILE", "HIKARICP_CONFIGURATION_FILE"])
    fun `the HikariCP configuration file property is refused in any form (INV-Q-4559)`(key: String) {
        val environment = if (key.first().isUpperCase()) environmentVariables(key to "/tmp/pool.properties") else properties(key to "/tmp/pool.properties")
        shouldThrow<IllegalStateException> { guard(environment) }.message.orEmpty() shouldContain "INV-Q-4559"
    }

    @Test
    fun `the HikariCP configuration file system property is refused (INV-Q-4559)`() {
        SqlConfigurationKeyGuard.findExternalConfiguration(emptyList()) { null }.shouldBeEmpty()
        SqlConfigurationKeyGuard.findExternalConfiguration(emptyList()) { name ->
            "/tmp/pool.properties".takeIf { name == "hikaricp.configurationFile" }
        }.single() shouldContain "hikaricp.configurationFile"
        val previous = System.setProperty("hikaricp.configurationFile", "/tmp/pool.properties")
        try {
            shouldThrow<IllegalStateException> { guard(properties()) }.message.orEmpty() shouldContain "hikaricp.configurationFile"
        } finally {
            if (previous == null) System.clearProperty("hikaricp.configurationFile") else System.setProperty("hikaricp.configurationFile", previous)
        }
    }

    @Test
    fun `a class-path pgjdbc driverconfig-properties is refused (INV-Q-4559)`(
        @TempDir root: Path,
    ) {
        val file = root.resolve("org/postgresql/driverconfig.properties")
        Files.createDirectories(file.parent)
        Files.writeString(file, "options=-c search_path=x\n")
        URLClassLoader(arrayOf(root.toUri().toURL()), null).use { loader ->
            val failure = shouldThrow<IllegalStateException> { SqlConfigurationKeyGuard.verify(properties(), loader) }
            failure.message.orEmpty() shouldContain "org/postgresql/driverconfig.properties"
            failure.message.orEmpty() shouldContain "INV-Q-4559"
        }
        SqlConfigurationKeyGuard.verify(properties(), URLClassLoader(arrayOf(), null))
    }

    companion object {
        val TPL_16 =
            arrayOf(
                "spring.datasource.url" to "jdbc:postgresql://db:5432/submission",
                "spring.datasource.username" to "submission_runtime",
                "spring.datasource.hikari.maximum-pool-size" to "7",
                "spring.datasource.hikari.minimum-idle" to "1",
                "spring.datasource.hikari.idle-timeout" to "60000",
                "spring.datasource.hikari.max-lifetime" to "1800000",
                "spring.flyway.user" to "submission_migrate",
                "spring.flyway.schemas" to "submission",
                "spring.flyway.default-schema" to "submission",
                "spring.flyway.locations" to "classpath:db/migration",
                "spring.flyway.placeholders.svc" to "submission",
                "gst.db.event-log-url" to "jdbc:postgresql://log:5432/submission",
                "service-runtime.datasource.credential" to "db/submission_runtime",
            )

        fun guard(environment: StandardEnvironment) = SqlConfigurationKeyGuard().postProcessEnvironment(environment, SpringApplication())

        /** An environment with only [entries], as a properties or YAML source (no real system properties or variables). */
        fun properties(vararg entries: Pair<String, String>): StandardEnvironment = empty().also {
            it.propertySources.addFirst(MapPropertySource("test", mapOf(*entries)))
        }

        /** An environment with only [entries], as environment variables. */
        fun environmentVariables(vararg entries: Pair<String, String>): StandardEnvironment = empty().also {
            it.propertySources.addFirst(
                SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, mapOf(*entries)),
            )
        }

        private fun empty(): StandardEnvironment = StandardEnvironment().also { environment ->
            environment.propertySources.toList().forEach { environment.propertySources.remove(it.name) }
        }

        fun flat(text: String) = text.lowercase().filter(Char::isLetterOrDigit)
    }
}
