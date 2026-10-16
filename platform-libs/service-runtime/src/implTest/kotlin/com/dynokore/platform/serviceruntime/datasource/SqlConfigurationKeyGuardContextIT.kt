package com.dynokore.platform.serviceruntime.datasource

import com.dynokore.platform.serviceruntime.ServiceRuntimeConfiguration
import com.dynokore.platform.serviceruntime.datasource.SqlConfigurationKeyGuardTest.Companion.TPL_16
import com.dynokore.platform.serviceruntime.health.Tpl18WebService
import com.dynokore.platform.serviceruntime.migration.MigrationRunner
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.jmx.JmxAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.EnableMBeanExport
import org.springframework.context.annotation.PropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.core.io.support.SpringFactoriesLoader
import org.springframework.jmx.export.MBeanExporter
import java.lang.management.ManagementFactory
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.nio.file.Path
import java.util.Properties
import javax.management.Attribute
import javax.management.InstanceNotFoundException
import javax.management.ObjectName
import javax.sql.DataSource

/**
 * Coordinator ruling INV-Q-4542 through SpringApplication: [SqlConfigurationKeyGuard] is registered in service-runtime's
 * `META-INF/spring.factories`, a service composed as TPL-18 with a TPL-16 configuration starts, a refused key fails
 * start-up (also as an environment variable), and the migration runner refuses `spring.flyway.init-sqls` before Flyway
 * connects.
 */
class SqlConfigurationKeyGuardContextIT {
    @Test
    fun `the guard is registered as an EnvironmentPostProcessor`() {
        val registered =
            javaClass.classLoader
                .getResources(SpringFactoriesLoader.FACTORIES_RESOURCE_LOCATION)
                .toList()
                .flatMap { url ->
                    val factories = Properties().apply { url.openStream().use(::load) }
                    factories
                        .getProperty(EnvironmentPostProcessor::class.java.name)
                        .orEmpty()
                        .split(',')
                        .map(String::trim)
                }
        registered shouldContain SqlConfigurationKeyGuard::class.java.name
    }

    @Test
    fun `a service composed as TPL-18 with a TPL-16 configuration starts`(
        @TempDir secrets: Path,
    ) {
        service().run(*arguments(secrets)).use { context ->
            context.isActive shouldBe true
            context.getBeanNamesForType(ServiceRuntimeConfiguration::class.java).size shouldBe 1
        }
    }

    @Test
    fun `start-up fails on a refused key from the command line, before the context exists`(
        @TempDir secrets: Path,
    ) {
        val failure =
            shouldThrow<IllegalStateException> {
                service().run(*arguments(secrets), "--spring.datasource.hikari.connection-init-sql=select 1").close()
            }
        failure.message.orEmpty() shouldContain "spring.datasource.hikari.connection-init-sql"
        failure.message.orEmpty() shouldContain "INV-Q-4542"
    }

    @Test
    fun `start-up fails on a refused key passed as a servlet-context parameter`(
        @TempDir secrets: Path,
    ) {
        val failure =
            shouldThrow<IllegalStateException> {
                service()
                    .run(
                        *arguments(secrets),
                        "--server.servlet.context-parameters.spring.datasource.hikari.schema=evil",
                    ).close()
            }
        failure.message.orEmpty() shouldContain "server.servlet.context-parameters.spring.datasource.hikari.schema"
        failure.message.orEmpty() shouldContain "INV-Q-4542"
    }

    @Test
    fun `start-up fails on a refused key from a @PropertySource file, before the DataSource bean is created`() {
        tpl18Service()
            .withPropertyValues(
                "spring.datasource.url=jdbc:postgresql://db:5432/submission",
                "spring.datasource.username=submission_runtime",
            ).withUserConfiguration(PropertySourceService::class.java)
            .run { context ->
                val failure = context.startupFailure.shouldNotBeNull()
                // the second run (SqlConfigurationKeyRecheck), not a bean creation, fails it (INV-Q-4559)
                failure.shouldBeInstanceOf<IllegalStateException>()
                failure.message.orEmpty() shouldContain "spring.datasource.hikari.connection-init-sql"
                failure.message.orEmpty() shouldContain "INV-Q-4559"
                failure.message.orEmpty() shouldNotContain "probe_ps"
            }
    }

    @Configuration(proxyBeanMethods = false)
    @PropertySource("classpath:sqlguard/property-source-init-sql.properties")
    class PropertySourceService

    @Test
    fun `start-up fails on @EnableMBeanExport or an MBeanExporter @Bean before the DataSource exists (INV-Q-4591)`() {
        mapOf(
            EnableMBeanExportService::class.java to "mbeanExporter",
            ExporterBeanService::class.java to "probeExporter",
        ).forEach { (service, bean) ->
            tpl18Service()
                .withPropertyValues(
                    "spring.datasource.url=jdbc:postgresql://db:5432/submission",
                    "spring.datasource.username=submission_runtime",
                ).withUserConfiguration(service)
                .run { context ->
                    val failure = context.startupFailure.shouldNotBeNull()
                    // the second run (SqlConfigurationKeyRecheck), not a bean creation, fails it
                    failure.shouldBeInstanceOf<IllegalStateException>()
                    failure.message.orEmpty() shouldContain "INV-Q-4591"
                    failure.message.orEmpty() shouldContain bean
                }
        }
    }

    @Test
    fun `the pool password cannot be set over JMX at run time (INV-Q-4591)`() {
        val service =
            tpl18Service()
                .withPropertyValues(
                    "spring.datasource.url=jdbc:postgresql://db:5432/submission",
                    "spring.datasource.username=submission_runtime",
                ).withConfiguration(AutoConfigurations.of(JmxAutoConfiguration::class.java))
        // spring.jmx.enabled=true as a key, and through a property source the key check cannot enumerate (the exporter check)
        service.withPropertyValues("spring.jmx.enabled=true").run { context ->
            context.startupFailure
                .shouldNotBeNull()
                .message
                .orEmpty() shouldContain "spring.jmx.enabled"
            hikariMBeans().shouldBeEmpty()
        }
        service.withInitializer(indirect("spring.jmx.enabled" to "true")).run { context ->
            context.startupFailure
                .shouldNotBeNull()
                .message
                .orEmpty() shouldContain "INV-Q-4591"
            hikariMBeans().shouldBeEmpty()
            shouldThrow<InstanceNotFoundException> {
                ManagementFactory.getPlatformMBeanServer().setAttribute(
                    ObjectName("com.zaxxer.hikari:name=dataSource,type=HikariDataSource"),
                    Attribute("Password", "pw-value"),
                )
            }
        }
        // service-runtime's defaults: no exporter, so no pool MBean
        service.run { context ->
            context.startupFailure shouldBe null
            context.getBeanNamesForType(MBeanExporter::class.java).shouldBeEmpty()
            applicationRegistersMbeans(context) shouldBe false
            hikariMBeans().shouldBeEmpty()
        }
    }

    // pool-inspection read (owner ruling INV-Q-4788 (B)): a getter in a method body of this class
    private fun applicationRegistersMbeans(context: ApplicationContext): Boolean =
        (context.getBean(DataSource::class.java) as HikariDataSource).isRegisterMbeans

    private fun hikariMBeans() =
        ManagementFactory.getPlatformMBeanServer().queryNames(ObjectName("com.zaxxer.hikari:*"), null)

    private fun indirect(vararg entries: Pair<String, String>) =
        ApplicationContextInitializer<ConfigurableApplicationContext> { context ->
            val values = mapOf(*entries)
            context.environment.propertySources.addFirst(
                object : org.springframework.core.env.PropertySource<Any>("indirect", Any()) {
                    override fun getProperty(name: String): Any? = values[name]
                },
            )
        }

    @Configuration(proxyBeanMethods = false)
    @EnableMBeanExport
    class EnableMBeanExportService

    @Configuration(proxyBeanMethods = false)
    class ExporterBeanService {
        @Bean
        fun probeExporter(): MBeanExporter = MBeanExporter()
    }

    @Test
    fun `start-up fails on a refused key set as an environment variable, naming the variable`(
        @TempDir secrets: Path,
    ) {
        val environment = StandardEnvironment()
        val variables = System.getenv() + mapOf("SPRING_FLYWAY_INITSQLS" to "select 1")
        environment.propertySources.replace(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables),
        )
        val failure =
            shouldThrow<IllegalStateException> { service().environment(environment).run(*arguments(secrets)).close() }
        failure.message.orEmpty() shouldContain "SPRING_FLYWAY_INITSQLS"
        failure.message.orEmpty() shouldContain "INV-Q-4542"
    }

    /**
     * Coordinator ruling INV-Q-4603: each key that removes a start-up guard or loads classes or bean definitions
     * fails start-up as a command-line property and as an environment variable, naming the key, never the value.
     */
    @ParameterizedTest(name = "{0} / {1}")
    @CsvSource(
        delimiter = '|',
        value = [
            "spring.autoconfigure.exclude|SPRING_AUTOCONFIGURE_EXCLUDE|com.dynokore.platform.serviceruntime.ServiceRuntimeConfiguration",
            "spring.autoconfigure.exclude[0]|SPRING_AUTOCONFIGURE_EXCLUDE_0|com.dynokore.platform.serviceruntime.ServiceRuntimeConfiguration",
            "spring.main.allow-bean-definition-overriding|SPRING_MAIN_ALLOW_BEAN_DEFINITION_OVERRIDING|true",
            "spring.main.allowBeanDefinitionOverriding|SPRING_MAIN_ALLOWBEANDEFINITIONOVERRIDING|yes",
            "spring.main.sources|SPRING_MAIN_SOURCES|classpath:probe-beans.xml",
            "spring.main.sources[1]|SPRING_MAIN_SOURCES_1|com.example.ProbeSource",
            "context.initializer.classes|CONTEXT_INITIALIZER_CLASSES|com.example.ProbeInitializer",
            "context.listener.classes|CONTEXT_LISTENER_CLASSES|com.example.ProbeListener",
            "spring.application.admin.enabled|SPRING_APPLICATION_ADMIN_ENABLED|true",
            "spring.datasource.driver-class-name|SPRING_DATASOURCE_DRIVER_CLASS_NAME|org.h2.Driver",
            "spring.datasource.driver-class-name|SPRING_DATASOURCE_DRIVERCLASSNAME|com.example.ProbeDriver",
        ],
    )
    fun `start-up fails on a key that removes a guard or loads classes or bean definitions (INV-Q-4603)`(
        key: String,
        variable: String,
        value: String,
        @TempDir secrets: Path,
    ) {
        val asProperty =
            shouldThrow<IllegalStateException> { service().run(*arguments(secrets), "--$key=$value").close() }
        // named in its relaxed form (spring.main.allowbeandefinitionoverriding)
        SqlConfigurationKeyGuardTest.flat(asProperty.message.orEmpty()) shouldContain
            SqlConfigurationKeyGuardTest.flat(key.substringBefore('['))
        asProperty.message.orEmpty() shouldContain "INV-Q-4603"

        val environment = StandardEnvironment()
        environment.propertySources.replace(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                System.getenv() + mapOf(variable to value),
            ),
        )
        val asVariable =
            shouldThrow<IllegalStateException> { service().environment(environment).run(*arguments(secrets)).close() }
        asVariable.message.orEmpty() shouldContain variable
        asVariable.message.orEmpty() shouldContain "INV-Q-4603"
        if (value.startsWith("com.example")) {
            asProperty.message.orEmpty() shouldNotContain value
            asVariable.message.orEmpty() shouldNotContain value
        }
    }

    @Test
    fun `the allowed values of those keys start (INV-Q-4603)`(
        @TempDir secrets: Path,
    ) {
        service()
            .run(
                *arguments(secrets),
                "--spring.main.allow-bean-definition-overriding=false",
                "--spring.application.admin.enabled=false",
                "--spring.datasource.driver-class-name=org.postgresql.Driver",
            ).use { context -> context.isActive shouldBe true }
    }

    @Test
    fun `the migration runner refuses spring-flyway-init-sqls before Flyway connects`(
        @TempDir mountRoot: Path,
    ) {
        ServerSocket(0).use { database ->
            val failure =
                shouldThrow<IllegalStateException> {
                    MigrationRunner.run(
                        "--spring.datasource.url=jdbc:postgresql://localhost:${database.localPort}/none",
                        "--gst.bindings.secret-store.mount-root=$mountRoot",
                        "--spring.flyway.user=submission_migrate",
                        "--service-runtime.flyway.credential=db-submission-migrate",
                        "--spring.flyway.init-sqls=set role postgres",
                    )
                }
            failure.message.orEmpty() shouldContain "spring.flyway.init-sqls"
            failure.message.orEmpty() shouldContain "INV-Q-4542"
            // no connection attempt reached the database port
            database.soTimeout = 200
            shouldThrow<SocketTimeoutException> { database.accept().close() }
        }
    }

    private fun service() = SpringApplicationBuilder(Tpl18WebService::class.java).web(WebApplicationType.SERVLET)

    // As ManagementPortIT: command-line arguments, no database (the TPL-16 database keys are present and unused).
    private fun arguments(secrets: Path): Array<String> =
        (
            listOf(
                "server.port=0",
                "management.server.port=0",
                "service-runtime.workload-identity.audience=submission-svc",
                "gst.service=submission-svc",
                "gst.bindings.secret-store.mount-root=$secrets",
                "spring.autoconfigure.exclude=" +
                    "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration," +
                    "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
            ) + TPL_16.map { (key, value) -> "$key=$value" }
        ).map { "--$it" }.toTypedArray()
}
