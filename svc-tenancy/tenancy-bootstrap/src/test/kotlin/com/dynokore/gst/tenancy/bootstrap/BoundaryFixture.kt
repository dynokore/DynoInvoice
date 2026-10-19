package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.platform.tenancykernel.SqlIdentifier
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.MountableFile
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/** One PostgreSQL with the service schema migrated as the installation does, and the context runner over it. */
object BoundaryFixture {
    private const val RUNTIME_PASSWORD = "runtime-password-for-tests"
    val container: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:17").withLabel("inv.agent", "c-inv-t-4925h4")

    private val migrationsDir: Path = Path.of("..", "tenancy-adapters", "src", "main", "resources", "db", "migration")
    private val roles: Path = Path.of("..", "tenancy-adapters", "src", "integrationTest", "resources", "roles.sql")

    init {
        container.start()
        Runtime.getRuntime().addShutdownHook(Thread { container.stop() })
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
            connection.autoCommit = true
            val script = connection.createStatement()
            runFile(roles)
            script.execute("CREATE SCHEMA IF NOT EXISTS tenancy")
            Files.list(migrationsDir).use { files ->
                files.filter { it.fileName.toString().endsWith(".sql") }.sorted().forEach { runFile(it) }
            }
            script.execute("ALTER ROLE tenancy_runtime LOGIN PASSWORD '$RUNTIME_PASSWORD'")
            val database = SqlIdentifier.require(container.databaseName, "database")
            script.execute("GRANT CONNECT ON DATABASE $database TO tenancy_runtime")
            script.execute("GRANT USAGE ON SCHEMA tenancy TO tenancy_runtime")
            script.execute(
                "INSERT INTO tenancy.binding_key (slot, secret) VALUES ('current', convert_to('${TestSecrets.BINDING_KEY}', 'UTF8'))",
            )
        }
    }

    /** Runs a SQL file as the container's superuser through psql, stopping at the first error. */
    private fun runFile(file: Path) {
        val target = "/tmp/${file.fileName}"
        container.copyFileToContainer(MountableFile.forHostPath(file), target)
        val result =
            container.execInContainer(
                "psql", "-v", "ON_ERROR_STOP=1", "-U", container.username, "-d", container.databaseName, "-f", target,
            )
        check(result.exitCode == 0) { "$file failed: ${result.stderr}" }
    }

    /** A superuser connection, for the assertions that must see every row. */
    fun <T> asSuperuser(block: (java.sql.Connection) -> T): T =
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use(block)

    /** A connection as the runtime login with nothing bound: forced row-level security hides every row. */
    fun <T> asRuntime(block: (java.sql.Connection) -> T): T =
        DriverManager.getConnection(container.jdbcUrl, "tenancy_runtime", RUNTIME_PASSWORD).use(block)

    fun runner(): WebApplicationContextRunner =
        WebApplicationContextRunner()
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withUserConfiguration(TenancyApplication::class.java)
            .withPropertyValues(
                "spring.datasource.url=${container.jdbcUrl}",
                "spring.datasource.username=tenancy_runtime",
                "service-runtime.workload-identity.audience=tenancy-svc",
                "gst.service=tenancy-svc",
                "gst.instance-id=tenancy-svc-0",
                "service-runtime.pool.profile=minimal",
                "service-runtime.event-loops.enabled=false",
                "gst.bindings.secret-store.mount-root=${TestSecrets.mount(RUNTIME_PASSWORD)}",
                "service-runtime.datasource.credential=db/tenancy_runtime",
            )
}
