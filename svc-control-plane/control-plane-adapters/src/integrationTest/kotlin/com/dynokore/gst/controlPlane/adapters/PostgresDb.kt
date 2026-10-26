package com.dynokore.gst.controlPlane.adapters

import com.dynokore.platform.tenancyjooq.TenancyProbe
import com.dynokore.platform.tenancykernel.BindingKeyMigration
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.nio.file.Files
import java.nio.file.Path
import javax.sql.DataSource

/**
 * One PostgreSQL per test JVM. The role family of provisioning (roles.sql, schema.sql) and every migration, applied as
 * controlplane_migrate in version order the way the migration Job does (TPL-12), run once as the container's init
 * script (coordinator ruling INV-Q-5245, route R1), so no test body issues DDL. Tests then act as the runtime login,
 * controlplane_runtime: NOSUPERUSER, NOBYPASSRLS, owns nothing (S2-05, V-01). Catalog and grant probes go through
 * [TenancyProbe], which refuses a superuser or BYPASSRLS session, so a grant assertion cannot pass vacuously.
 */
object PostgresDb {
    private const val RUNTIME_LOGIN = "controlplane_runtime"
    private const val RUNTIME_PASSWORD = "controlplane_runtime_pw"

    private val container: PostgreSQLContainer<*> =
        PostgreSQLContainer(DockerImageName.parse(System.getProperty("gst.pg.image", "postgres:18")))
            .withLabel("inv.agent", "c-inv-t-4984")

    init {
        val script = Files.createTempFile("controlplane-init-", ".sql")
        try {
            Files.writeString(script, initSql())
            container.withCopyFileToContainer(
                MountableFile.forHostPath(script, 0b110_100_100),
                "/docker-entrypoint-initdb.d/10-init.sql",
            )
            container.start()
        } finally {
            Files.deleteIfExists(script)
        }
        Runtime.getRuntime().addShutdownHook(Thread { container.stop() })
    }

    private fun initSql(): String {
        val sql = Path.of(System.getProperty("gst.sql.dir"))
        val migrations =
            Files.list(Path.of(System.getProperty("gst.migrations.dir"))).use { files ->
                files
                    .filter { it.fileName.toString().endsWith(".sql") }
                    .sorted()
                    .map { Files.readString(it) }
                    .toList()
            }
        return listOf(Files.readString(sql.resolve("roles.sql")), Files.readString(sql.resolve("schema.sql")))
            .plus(bindingFunctions())
            .plus("SET ROLE controlplane_migrate;")
            .plus(migrations)
            .plus("RESET ROLE;")
            .joinToString("\n")
    }

    /**
     * The signed-binding functions of tenancy-kernel, which the tenant policy of the outbox and idempotency tables names.
     * Provisioning installs them as controlplane_sys before the migration Job runs (INV-T-4156, TPL-12); the schema
     * is owned by controlplane_migrate, so sys gets CREATE on it for this one statement group only.
     */
    private fun bindingFunctions(): String =
        listOf(
            "GRANT USAGE, CREATE ON SCHEMA controlplane TO controlplane_sys;",
            "SET ROLE controlplane_sys;",
            BindingKeyMigration.ddl("controlplane", "controlplane_sys", RUNTIME_LOGIN),
            "RESET ROLE;",
            "REVOKE CREATE ON SCHEMA controlplane FROM controlplane_sys;",
        ).joinToString("\n")

    /** The runtime login's data source: every statement of the service under test runs on it. */
    val runtimeDataSource: DataSource =
        PGSimpleDataSource().apply {
            setUrl(container.jdbcUrl)
            user = RUNTIME_LOGIN
            password = RUNTIME_PASSWORD
        }

    /**
     * The migration login, which owns the schema: information_schema lists a table only to a login with a privilege on it,
     * and the runtime login holds none on a table it reaches through a definer function only (job_retention).
     */
    fun ownerDsl(): DSLContext =
        DSL.using(
            PGSimpleDataSource().apply {
                setUrl(container.jdbcUrl)
                user = "controlplane_migrate"
                password = "controlplane_migrate_pw"
            },
            SQLDialect.POSTGRES,
        )

    /** The catalog and grant probe of TPL-14, on the runtime login. */
    val probe: TenancyProbe by lazy { TenancyProbe.connect(container.jdbcUrl, RUNTIME_LOGIN, RUNTIME_PASSWORD) }

    /** A plain jOOQ context on the runtime login (the tables here are platform-scoped: no tenant listeners apply). */
    fun dsl(): DSLContext = DSL.using(runtimeDataSource, SQLDialect.POSTGRES)
}
