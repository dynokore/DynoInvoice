package com.dynokore.gst.consoleBff.adapters

import org.flywaydb.core.Flyway
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration

/**
 * A PostgreSQL 18 database with the roles of `db/roles.sql`, the schema `console_bff` created by provisioning
 * (`db/provision.sql`, owned by `console_bff_migrate`) and the Flyway migrations of this module applied by the same
 * role (TPL-12, TPL-14). The two SQL files are run by the postgres image as superuser when it initialises.
 */
object ConsoleDb {
    const val SCHEMA = "console_bff"
    const val MIGRATE = "console_bff_migrate"
    const val RUNTIME = "console_bff_runtime"
    const val MIGRATE_PASSWORD = "console_bff_migrate_pw"
    const val RUNTIME_PASSWORD = "console_bff_runtime_pw"

    /** postgres:18 pinned by digest, as platform-libs' testcontainers-pins table pins it (INV-T-5604). */
    private const val IMAGE = "postgres:18@sha256:74935e72241653ca55e0414067e6d8763aceb8a810eb51b452253ec3dcfc4336"
    private const val INIT_DIRECTORY = "/docker-entrypoint-initdb.d/"

    // Started once per JVM. A failed start or migration is kept and rethrown, never retried, and the container stopped.
    private val started: Result<PostgreSQLContainer<*>> by lazy {
        runCatching {
            val postgres =
                PostgreSQLContainer(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
                    .withStartupTimeout(Duration.ofMinutes(PG_START_TIMEOUT_MINUTES))
                    .withStartupAttempts(PG_START_ATTEMPTS)
                    .withCommand(*pgFastCommand)
                    .withLabel("inv.agent", "c-inv-t-14458")
                    .withCopyFileToContainer(
                        MountableFile.forClasspathResource("db/roles.sql"),
                        "${INIT_DIRECTORY}00-roles.sql",
                    ).withCopyFileToContainer(
                        MountableFile.forClasspathResource("db/provision.sql"),
                        "${INIT_DIRECTORY}01-provision.sql",
                    )
            postgres.start()
            runCatching { migrate(postgres) }.onFailure { postgres.stop() }.getOrThrow()
            postgres
        }
    }

    private val container: PostgreSQLContainer<*> get() = started.getOrThrow()

    val jdbcUrl: String get() = container.jdbcUrl

    fun superuser(): Connection = DriverManager.getConnection(container.jdbcUrl, container.username, container.password)

    fun connect(
        user: String,
        password: String,
    ): Connection = DriverManager.getConnection(container.jdbcUrl, user, password)

    private fun migrate(postgres: PostgreSQLContainer<*>) {
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, MIGRATE, MIGRATE_PASSWORD)
            .schemas(SCHEMA)
            .defaultSchema(SCHEMA)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }
}

// CI-FAST: a loaded CI host can need more than the 60 s Testcontainers default for initdb and its restart.
private const val PG_START_TIMEOUT_MINUTES = 3L
private const val PG_START_ATTEMPTS = 3
private val pgFastCommand =
    arrayOf("postgres", "-c", "fsync=off", "-c", "synchronous_commit=off", "-c", "full_page_writes=off")
