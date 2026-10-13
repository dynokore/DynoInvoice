package com.dynokore.platform.serviceruntime.datasource

import com.dynokore.platform.serviceruntime.composition.ServiceDataSources
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.PropertySource
import javax.sql.DataSource

/**
 * Coordinator ruling INV-Q-4542 on what each pool actually receives: [PoolConfigurationGuard] classifies every
 * HikariConfig property (lessons P140) and, in a TPL-18 composition, refuses a pool configured to run SQL or set session
 * state, whatever path the value took (here a property bound with the key guard bypassed, as an indirect path would be).
 */
class PoolConfigurationGuardTest {
    @Test
    fun `every HikariConfig property is classified once (fails when HikariCP adds a property)`() {
        val classified = PoolConfigurationGuard.ALLOWED.keys + PoolConfigurationGuard.AT_DEFAULT
        (PoolConfigurationGuard.ALLOWED.keys intersect PoolConfigurationGuard.AT_DEFAULT).shouldBeEmpty()
        (PoolConfigurationGuard.PROPERTIES.keys - classified).shouldBeEmpty()
        (classified - PoolConfigurationGuard.PROPERTIES.keys).shouldBeEmpty()
    }

    @Test
    fun `a pool at the allowed settings passes`() {
        val pool = allowedPool()
        PoolConfigurationGuard.findOffendingProperties(pool, SERVICE).shouldBeEmpty()
    }

    @Test
    fun `dataSourceProperties may hold only ApplicationName equal to gst-service (INV-Q-4487, INV-Q-4566)`() {
        // ApplicationName equal to gst.service: passes
        PoolConfigurationGuard.findOffendingProperties(allowedPool(), SERVICE).shouldBeEmpty()
        // no data-source property at all: passes
        PoolConfigurationGuard.findOffendingProperties(allowedPool().apply { dataSourceProperties.clear() }, SERVICE).shouldBeEmpty()
        // ApplicationName other than gst.service: refused, the value not printed
        val other = allowedPool().apply { addDataSourceProperty("ApplicationName", "other-svc") }
        PoolConfigurationGuard.findOffendingProperties(other, SERVICE) shouldBe listOf("dataSourceProperties (ApplicationName other than gst.service)")
        // ApplicationName with no gst.service set: refused
        PoolConfigurationGuard.findOffendingProperties(allowedPool(), null) shouldBe listOf("dataSourceProperties (ApplicationName other than gst.service)")
        // another property beside a correct ApplicationName: refused, naming only the other property
        val extra = allowedPool().apply { addDataSourceProperty("currentSchema", "evil") }
        PoolConfigurationGuard.findOffendingProperties(extra, SERVICE) shouldBe listOf("dataSourceProperties (refused data-source properties [currentSchema])")
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "connectionInitSql", "connectionTestQuery", "schema", "catalog", "transactionIsolation", "autoCommit",
            "readOnly", "dataSourceClassName", "options", "jdbcUrlOptions", "jdbcUrlPassword", "applicationName",
        ],
    )
    fun `a pool that runs SQL or sets session state is refused, and the value is not printed`(setting: String) {
        val pool = allowedPool()
        when (setting) {
            "connectionInitSql" -> pool.connectionInitSql = "set role postgres"
            "connectionTestQuery" -> pool.connectionTestQuery = "select 1"
            "schema" -> pool.schema = "evil"
            "catalog" -> pool.catalog = "evil"
            "transactionIsolation" -> pool.transactionIsolation = "TRANSACTION_SERIALIZABLE"
            "autoCommit" -> pool.isAutoCommit = false
            "readOnly" -> pool.isReadOnly = true
            "dataSourceClassName" -> pool.dataSourceClassName = "org.postgresql.ds.PGSimpleDataSource"
            "options" -> pool.addDataSourceProperty("options", "-c search_path=evil")
            "jdbcUrlOptions" -> pool.jdbcUrl = "jdbc:postgresql://db/app?options=-c%20search_path%3Devil"
            "jdbcUrlPassword" -> pool.jdbcUrl = "jdbc:postgresql://db/app?password=evil"
            "applicationName" -> pool.addDataSourceProperty("ApplicationName", "evil")
        }
        val offending = PoolConfigurationGuard.findOffendingProperties(pool, SERVICE)
        offending.size shouldBe 1
        offending.single() shouldNotContain "evil"
        offending.single() shouldNotContain "postgres"
    }

    @Test
    fun `a TPL-18 service whose application pool is bound with a refused setting through an indirect path fails start-up`() {
        tpl18Service()
            .withPropertyValues(
                "spring.datasource.url=jdbc:postgresql://db:5432/submission",
                "spring.datasource.username=submission_runtime",
            ).withInitializer(
                indirect("spring.datasource.hikari.schema" to "evil", "spring.datasource.hikari.connection-test-query" to "select 1"),
            ).run { context ->
                val failure = generateSequence(context.startupFailure) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
                failure shouldContain "INV-Q-4542"
                failure shouldContain "schema (set)"
                failure shouldContain "connectionTestQuery (set)"
            }
    }

    @Test
    fun `a TPL-18 service whose event-log URL carries options fails start-up, a plain one starts`() {
        val service =
            tpl18Service().withPropertyValues(
                "spring.datasource.url=jdbc:postgresql://db:5432/submission",
                "spring.datasource.username=submission_runtime",
            )
        service.withPropertyValues("gst.db.event-log-url=jdbc:postgresql://log:5432/submission?loggerLevel=OFF&sslmode=verify-full&sslrootcert=/etc/ssl/pg.crt").run { context ->
            context.startupFailure shouldBe null
            // the service-runtime pools with their ApplicationName <gst.service> pass (INV-Q-4566)
            val eventLog = context.getBean(ServiceDataSources.EVENT_LOG, HikariDataSource::class.java)
            eventLog.dataSourceProperties["ApplicationName"] shouldBe "submission-svc"
            context.getBean(DataSource::class.java).unwrap(HikariDataSource::class.java).dataSourceProperties["ApplicationName"] shouldBe "submission-svc"
        }
        service.withInitializer(indirect("gst.db.event-log-url" to "jdbc:postgresql://log:5432/submission?options=-c%20search_path%3Dx")).run { context ->
            val failure = generateSequence(context.startupFailure) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
            failure shouldContain ServiceDataSources.EVENT_LOG
            failure shouldContain "jdbcUrl (URL parameters not on the allowlist (INV-Q-4559): [options])"
            failure shouldNotContain "search_path"
        }
    }

    /**
     * A property source that cannot be enumerated, so the key check does not see it but Spring binds from it: the
     * shape of an indirect path (as Tomcat's servlet-context parameters before the web server exists).
     */
    private fun indirect(vararg entries: Pair<String, String>) = ApplicationContextInitializer<ConfigurableApplicationContext> { context ->
        val values = mapOf(*entries)
        context.environment.propertySources.addFirst(
            object : PropertySource<Any>("indirect", Any()) {
                override fun getProperty(name: String): Any? = values[name]
            },
        )
    }

    private fun allowedPool(): HikariConfig = HikariConfig().apply {
        jdbcUrl = "jdbc:postgresql://db:5432/app?sslmode=require"
        username = "submission_runtime"
        maximumPoolSize = 7
        minimumIdle = 1
        poolName = "app"
        addDataSourceProperty("ApplicationName", SERVICE)
    }

    private companion object {
        const val SERVICE = "submission-svc"
    }
}
