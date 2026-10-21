package com.dynokore.gst.rules.bootstrap

import com.dynokore.platform.serviceruntime.migration.MigrationRunner
import com.dynokore.platform.tenancykernel.BindingKeyMigration
import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.nio.file.Files
import java.nio.file.Path

private const val BINDING_KEY_HEX = "0101010101010101010101010101010101010101010101010101010101010101"

/**
 * The service on real PostgreSQL, the way TPL-14 builds it: the roles of TPL-12 (rules_migrate owns the schema and
 * every table, rules_runtime is the one runtime login, rules_sys owns the definer functions) and the migrations run
 * through service-runtime's MigrationRunner as rules_migrate. One container per JVM, shared by the integration tests.
 */
object PostgresRules {
    val secrets: Path =
        Files.createTempDirectory("rules-it-secrets").also { root ->
            Files.createDirectories(root.resolve("db"))
            Files.createDirectories(root.resolve("tenancy"))
            Files.writeString(root.resolve("db/rules_runtime"), "rules_runtime_pw")
            Files.writeString(root.resolve("db/rules_migrate"), "rules_migrate_pw")
            // 32 bytes of 0x01: the key TenancyProbe signs its bindings with (SignedBinding.key())
            Files.writeString(root.resolve("tenancy/binding-key-current"), "\u0001".repeat(32))
        }

    val initSql: Path =
        Files.createTempFile("rules-init", ".sql").also { file ->
            // statement groups joined outside SQL text (INV-REQ-854 b5): no generated SQL is spliced into a literal
            val roles =
                listOf(
                    "CREATE ROLE rules_migrate LOGIN PASSWORD 'rules_migrate_pw';",
                    "CREATE ROLE rules_runtime LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD 'rules_runtime_pw';",
                    "CREATE ROLE rules_sys NOLOGIN;",
                    "GRANT rules_sys TO rules_migrate;",
                    "CREATE EXTENSION IF NOT EXISTS pgcrypto SCHEMA public;",
                    "CREATE SCHEMA rules AUTHORIZATION rules_migrate;",
                    "GRANT USAGE, CREATE ON SCHEMA rules TO rules_sys;",
                    "GRANT USAGE ON SCHEMA rules TO rules_runtime;",
                )
            val binding =
                listOf(
                    "SET ROLE rules_sys;",
                    BindingKeyMigration.ddl("rules", "rules_sys", "rules_runtime"),
                    // 32 bytes of 0x01: the key TenancyProbe signs its bindings with (SignedBinding.key())
                    "INSERT INTO rules.binding_key (slot, secret) VALUES ('current', decode('" + BINDING_KEY_HEX +
                        "', 'hex'));",
                    "RESET ROLE;",
                    "REVOKE CREATE ON SCHEMA rules FROM rules_sys;",
                )
            Files.writeString(file, (roles + binding).joinToString("\n"))
            file.toFile().setReadable(true, false)
        }

    /** Testcontainers needs a concrete subclass for its fluent `self()` typing. */
    class Postgres(
        image: DockerImageName,
    ) : PostgreSQLContainer<Postgres>(image)

    /** Started once for the class and stopped with the JVM (Ryuk); the migrations run in [register]. */
    val pg: Postgres =
        Postgres(DockerImageName.parse(System.getProperty("gst.pg.image", "postgres:18")))
            .withCopyFileToContainer(
                MountableFile.forHostPath(initSql, 420),
                "/docker-entrypoint-initdb.d/00-init.sql",
            ).also { it.start() }

    fun register(registry: DynamicPropertyRegistry) {
        // The service never migrates at start-up: the test migrates first through MigrationRunner as
        // rules_migrate, as the migration Job does (TPL-12, ARC-20).
        MigrationRunner
            .run(
                "--spring.datasource.url=${pg.jdbcUrl}",
                "--gst.bindings.secret-store.mount-root=$secrets",
                "--spring.flyway.user=rules_migrate",
                "--spring.flyway.schemas=rules",
                "--spring.flyway.default-schema=rules",
                // The schema already holds the signed-binding objects the sys login created (INV-Q-6580),
                // so Flyway needs a baseline below V1 (the installation decides this, not the test).
                "--spring.flyway.baseline-on-migrate=true",
                "--spring.flyway.baseline-version=0",
                "--service-runtime.flyway.credential=db/rules_migrate",
                "--gst.service=rules-svc",
                "--service-runtime.modules.schema=rules",
            ).close()
        registry.add("spring.datasource.url") { pg.jdbcUrl }
        registry.add("gst.bindings.secret-store.mount-root") { secrets.toString() }
        registry.add("gst.instance-id") { "rules-svc-0" }
        registry.add("service-runtime.workload-identity.audience") { "rules-svc" }
        registry.add("service-runtime.pool.profile") { "minimal" }
        registry.add("management.server.port") { "0" }
        registry.add("server.port") { "0" }
    }
}
