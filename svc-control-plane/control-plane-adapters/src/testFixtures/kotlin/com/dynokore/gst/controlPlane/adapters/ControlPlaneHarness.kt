package com.dynokore.gst.controlPlane.adapters

import com.dynokore.platform.tenancykernel.BindingKeyMigration
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.postgresql.ds.PGSimpleDataSource
import java.nio.file.Files
import java.nio.file.Path

/**
 * What the integration-test harness adds to the provisioning scripts (INV-Q-10984 B): the signed-binding functions of
 * tenancy-kernel, which the tenant policy of the outbox names, installed before the first migration, and the
 * migration login's jOOQ context. The Gradle task harnessInitSql runs [main] to stage the scripts PostgresDb loads.
 */
object ControlPlaneHarness {
    /**
     * Provisioning installs the functions as controlplane_sys before the migration Job runs (INV-T-4156, TPL-12);
     * the schema is owned by controlplane_migrate, so sys gets CREATE on it for this one statement group only.
     */
    fun bindingFunctions(): String =
        listOf(
            "GRANT USAGE, CREATE ON SCHEMA controlplane TO controlplane_sys;",
            "SET ROLE controlplane_sys;",
            BindingKeyMigration.ddl("controlplane", "controlplane_sys", "controlplane_runtime"),
            "RESET ROLE;",
            "REVOKE CREATE ON SCHEMA controlplane FROM controlplane_sys;",
        ).joinToString("\n")

    /**
     * The migration login, which owns the schema: information_schema lists a table only to a login with a privilege
     * on it, and the runtime login holds none on a table it reaches through a definer function only (job_retention).
     */
    fun ownerDsl(): DSLContext =
        DSL.using(
            PGSimpleDataSource().apply {
                setUrl((PostgresDb.runtimeDataSource as PGSimpleDataSource).getUrl())
                user = "controlplane_migrate"
                password = "controlplane_migrate_pw"
            },
            SQLDialect.POSTGRES,
        )

    /** Arguments: the source directory (roles.sql, schema.sql) and the target directory the harness loads. */
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "expected a source directory and a target directory, got ${args.size} arguments" }
        val source = Path.of(args[0])
        val target = Path.of(args[1])
        val roles = Files.readString(source.resolve("roles.sql"))
        val schema = Files.readString(source.resolve("schema.sql"))
        Files.createDirectories(target)
        Files.writeString(target.resolve("roles.sql"), roles)
        Files.writeString(target.resolve("schema.sql"), schema + "\n" + bindingFunctions())
    }
}
