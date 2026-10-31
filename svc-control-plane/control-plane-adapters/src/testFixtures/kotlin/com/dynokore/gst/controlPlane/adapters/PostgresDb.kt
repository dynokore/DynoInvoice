package com.dynokore.gst.controlPlane.adapters

import com.dynokore.platform.tenancyjooq.TenancyProbe
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import javax.sql.DataSource

/**
 * One PostgreSQL per test JVM. The role family of provisioning (roles.sql, schema.sql) runs once as the container's
 * init script (coordinator ruling INV-Q-5245, route R1); then Flyway applies every migration as controlplane_migrate
 * in version order, the way the migration Job does (TPL-12, twelve-factor dev/prod parity: Flyway drops U+FEFF from
 * the scripts it reads, so the blank checks here are the ones production gets), so no test body issues DDL. Tests act
 * as the runtime login, controlplane_runtime: NOSUPERUSER, NOBYPASSRLS, owns nothing (S2-05, V-01). Catalog and grant
 * probes go through [TenancyProbe], which refuses a superuser or BYPASSRLS session, so a grant assertion cannot pass
 * vacuously.
 */
object PostgresDb {
    private const val RUNTIME_LOGIN = "controlplane_runtime"
    private const val RUNTIME_PASSWORD = "controlplane_runtime_pw"
    private const val STARTUP_MINUTES = 3L
    private const val MIGRATE_LOGIN = "controlplane_migrate"
    private const val MIGRATE_PASSWORD = "controlplane_migrate_pw"

    private val container: PostgreSQLContainer<*> =
        PostgreSQLContainer(DockerImageName.parse(System.getProperty("gst.pg.image", "postgres:18")))
            .withLabel("inv.agent", "c-inv-t-4985")
            // a saturated CI runner starts the container slowly: the default 60 s start-up wait is too short
            .withStartupTimeout(Duration.ofMinutes(STARTUP_MINUTES))

    init {
        val script = Files.createTempFile("controlplane-init-", ".sql")
        try {
            Files.writeString(script, initSql())
            container.withCopyFileToContainer(
                MountableFile.forHostPath(script, 0b110_100_100),
                "/docker-entrypoint-initdb.d/10-init.sql",
            )
            container.start()
            migrate()
        } finally {
            Files.deleteIfExists(script)
        }
        Runtime.getRuntime().addShutdownHook(Thread { container.stop() })
    }

    private fun initSql(): String {
        val sql = Path.of(System.getProperty("gst.sql.dir"))
        return listOf(Files.readString(sql.resolve("roles.sql")), Files.readString(sql.resolve("schema.sql")))
            .joinToString("\n")
    }

    /** The migration files of the service, applied by Flyway as the migration login, which owns the schema. */
    private fun migrate() {
        Flyway
            .configure()
            .dataSource(container.jdbcUrl, MIGRATE_LOGIN, MIGRATE_PASSWORD)
            .locations("filesystem:" + Path.of(System.getProperty("gst.migrations.dir")))
            .schemas("controlplane")
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .load()
            .migrate()
    }

    /** The runtime login's data source: every statement of the service under test runs on it. */
    val runtimeDataSource: DataSource =
        PGSimpleDataSource().apply {
            setUrl(container.jdbcUrl)
            user = RUNTIME_LOGIN
            password = RUNTIME_PASSWORD
        }

    /** The container's superuser, for tests that read the catalog or count rows past row-level security. */
    fun adminConnection(): java.sql.Connection =
        java.sql.DriverManager.getConnection(container.jdbcUrl, container.username, container.password)

    /** The catalog and grant probe of TPL-14, on the runtime login. */
    val probe: TenancyProbe by lazy { TenancyProbe.connect(container.jdbcUrl, RUNTIME_LOGIN, RUNTIME_PASSWORD) }

    /** A plain jOOQ context on the runtime login (the tables here are platform-scoped: no tenant listeners apply). */
    fun dsl(): DSLContext = DSL.using(runtimeDataSource, SQLDialect.POSTGRES)
}
