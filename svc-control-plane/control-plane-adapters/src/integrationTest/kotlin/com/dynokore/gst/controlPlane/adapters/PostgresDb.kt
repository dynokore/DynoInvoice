package com.dynokore.gst.controlPlane.adapters

import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * One PostgreSQL per test JVM with the role family of provisioning (roles.sql, schema.sql) and every migration applied
 * as controlplane_migrate, in version order, the way the migration Job does (TPL-12). Tests then act as the runtime
 * login, controlplane_runtime: NOSUPERUSER, NOBYPASSRLS, owns nothing (S2-05, V-01). A superuser session would make every
 * grant assertion pass vacuously, so [asRuntime] refuses one.
 */
object PostgresDb {
    private val container: PostgreSQLContainer<*> =
        PostgreSQLContainer(DockerImageName.parse(System.getProperty("gst.pg.image", "postgres:18")))
            .withLabel("inv.agent", "c-inv-t-4984")

    init {
        container.start()
        Runtime.getRuntime().addShutdownHook(Thread { container.stop() })
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { admin ->
            listOf("roles.sql", "schema.sql").forEach { admin.createStatement().execute(Files.readString(sql(it))) }
        }
        connect("controlplane_migrate", "controlplane_migrate_pw").use { migrate ->
            Files.list(Path.of(System.getProperty("gst.migrations.dir"))).use { files ->
                files.filter { it.fileName.toString().endsWith(".sql") }.sorted().forEach {
                    migrate.createStatement().execute(Files.readString(it))
                }
            }
        }
    }

    private fun sql(name: String): Path = Path.of(System.getProperty("gst.sql.dir"), name)

    fun connect(
        user: String,
        password: String,
    ): Connection = DriverManager.getConnection(container.jdbcUrl, user, password)

    /** A session of the runtime login; refuses a superuser or BYPASSRLS session. */
    fun asRuntime(): Connection =
        connect("controlplane_runtime", "controlplane_runtime_pw").also { connection ->
            connection
                .createStatement()
                .executeQuery(
                    "select rolsuper or rolbypassrls from pg_roles where rolname = current_user",
                ).use {
                    check(it.next() && !it.getBoolean(1)) { "the probe session must not be a superuser or bypass RLS" }
                }
        }

    /** Returns Unit on purpose: a JUnit test method that returns a value is not run. */
    fun runtime(block: (Connection, DSLContext) -> Unit) {
        asRuntime().use { connection -> block(connection, DSL.using(connection, SQLDialect.POSTGRES)) }
    }

    /** Calls the sys-owned function the way the stage will, over a runtime session. */
    fun scalar(
        connection: Connection,
        sql: String,
    ): Long =
        connection.createStatement().executeQuery(sql).use {
            it.next()
            it.getLong(1)
        }
}
