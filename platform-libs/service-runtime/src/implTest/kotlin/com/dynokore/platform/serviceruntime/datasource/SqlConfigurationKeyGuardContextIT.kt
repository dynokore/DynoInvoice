package com.dynokore.platform.serviceruntime.datasource

import com.dynokore.platform.serviceruntime.ServiceRuntimeConfiguration
import com.dynokore.platform.serviceruntime.datasource.SqlConfigurationKeyGuardTest.Companion.TPL_16
import com.dynokore.platform.serviceruntime.health.Tpl18WebService
import com.dynokore.platform.serviceruntime.migration.MigrationRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.core.io.support.SpringFactoriesLoader
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.nio.file.Path
import java.util.Properties

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
            javaClass.classLoader.getResources(SpringFactoriesLoader.FACTORIES_RESOURCE_LOCATION).toList().flatMap { url ->
                val factories = Properties().apply { url.openStream().use(::load) }
                factories.getProperty(EnvironmentPostProcessor::class.java.name).orEmpty().split(',').map(String::trim)
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
    private fun arguments(secrets: Path): Array<String> = (
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
