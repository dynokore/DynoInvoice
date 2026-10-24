package com.dynokore.gst.reconciliation.adapters

import org.flywaydb.core.Flyway
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.sql.Connection
import java.sql.DriverManager

/**
 * A PostgreSQL 18 database with the roles of INV-AC-2175, the signed-binding functions of the schema and the Flyway
 * migrations of this module applied by `reconciliation_migrate` (TPL-12, TPL-14). The roles (`db/roles.sql`) and the
 * binding functions (`db/provision.sql`, installed by the sys login as the installer does; coordinator rulings
 * INV-Q-6586 and INV-Q-6663) are SQL files the postgres image runs as superuser when it initialises. This class holds
 * no SQL text: the migrations do not run the binding generator.
 */
object ReconciliationDb {
    const val SCHEMA = "reconciliation"
    const val MIGRATE = "reconciliation_migrate"
    const val RUNTIME = "reconciliation_runtime"
    const val SYS = "reconciliation_sys"
    const val MIGRATE_PASSWORD = "reconciliation_migrate_pw"
    const val RUNTIME_PASSWORD = "reconciliation_runtime_pw"

    /** The value of the `tenancy/binding-key-current` secret: the key is its UTF-8 bytes (service-runtime README). */
    const val BINDING_SECRET = "reconciliation-test-binding-key-0001"

    /** postgres:18 pinned by digest, as platform-libs' testcontainers-pins table pins it (INV-T-5604). */
    private const val IMAGE = "postgres:18@sha256:74935e72241653ca55e0414067e6d8763aceb8a810eb51b452253ec3dcfc4336"
    private const val INIT_DIRECTORY = "/docker-entrypoint-initdb.d/"

    // Started once per JVM. A failed start or migration is kept and rethrown, never retried (a retry would start
    // another container per access), and the container is stopped.
    private val started: Result<PostgreSQLContainer<*>> by lazy {
        runCatching {
            val postgres =
                PostgreSQLContainer(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
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

    /** The installation key the runners sign with and the schema's `bound_tenant()` verifies. */
    val bindingKey: ByteArray = BINDING_SECRET.toByteArray(Charsets.UTF_8)

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
            // the installer left the binding functions in the schema: baseline it at 0 so V1 still runs
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .load()
            .migrate()
    }
}
