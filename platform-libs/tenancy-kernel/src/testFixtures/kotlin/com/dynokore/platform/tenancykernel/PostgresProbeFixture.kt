package com.dynokore.platform.tenancykernel

import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.nio.file.Files
import java.nio.file.Path

/** Testcontainers needs a concrete subclass for its fluent `self()` typing (Kotlin, unlike Java, has no raw types). */
private class Postgres(
    image: DockerImageName,
) : PostgreSQLContainer<Postgres>(image)

/**
 * Isolation-test fixtures (done-when, §9.2/§13.3): starts a real PostgreSQL
 * container running the two roles [tenancy-kernel-test-roles.sql] creates and the probe table, and names the
 * container's URL and logins — so a test proves RLS against the actual database CS-19 targets, not a fake.
 *
 * It names no `java.sql`, `javax.sql` or `org.postgresql` type (coordinator ruling INV-Q-5208): a test builds its pool
 * from [jdbcUrl] and the login strings through [TestPoolFixture]. The probe table `tenancy_kernel_test.probe` is
 * created at container start by the init script: the roles script, `SET ROLE` to the owner, the table and the text of
 * [TenantRlsMigration.ddl], generated here and never a static copy.
 *
 * One instance per test class, started once and stopped in [close]; a test class typically wants one per PostgreSQL
 * version under test (`@ParameterizedTest` over `postgres:17` and `postgres:18`, the done-when's own matrix), so this is
 * a value a test constructs itself rather than a shared JUnit extension singleton.
 */
class PostgresProbeFixture(
    image: String,
    /** More provisioning SQL, run once at container start as the owner role after the probe table (INV-Q-5208). */
    initSql: String = "",
) : AutoCloseable {
    private val script: Path = Files.createTempFile("tenancy-kernel-init", ".sql")

    private val container: Postgres

    init {
        val roles =
            checkNotNull(PostgresProbeFixture::class.java.getResourceAsStream("/db/tenancy-kernel-test-roles.sql")) {
                "db/tenancy-kernel-test-roles.sql is missing"
            }.use { String(it.readAllBytes(), Charsets.UTF_8) }
        Files.writeString(script, roles + "\n" + probeTableScript(initSql))
        container =
            Postgres(DockerImageName.parse(image))
                .withCopyFileToContainer(
                    MountableFile.forHostPath(script, READABLE),
                    "/docker-entrypoint-initdb.d/00-init.sql",
                )
        container.start()
    }

    /** The JDBC URL of the running container, for a test that builds its own pool (see [TestPoolFixture]). */
    fun jdbcUrl(): String = container.jdbcUrl

    override fun close() {
        try {
            container.stop()
        } finally {
            Files.deleteIfExists(script)
        }
    }

    /** The probe table this module's own tests exercise the RLS mechanism against, as the owner role. */
    private fun probeTableScript(initSql: String): String =
        """
        SET ROLE $OWNER;
        CREATE TABLE tenancy_kernel_test.probe (
            id        uuid PRIMARY KEY,
            tenant_id uuid NOT NULL,
            value     text NOT NULL
        );
        ${TenantRlsMigration.ddl(schema = "tenancy_kernel_test", table = "probe")}
        $initSql
        RESET ROLE;
        """.trimIndent()

    companion object {
        /** The init script is read by the container's postgres user: mode 0644 (a temp file is 0600). */
        private const val READABLE = 420

        /** The migration/owner role — DDL only. */
        const val OWNER = "tenancy_kernel_owner"
        const val OWNER_PASSWORD = "tenancy_kernel_owner_pw"

        /**
         * The runtime application role — `NOSUPERUSER`, `NOBYPASSRLS`, owns no table (S2-05): every query as this login
         * is subject to the same RLS a real service's connection would be.
         */
        const val APP = "tenancy_kernel_app"
        const val APP_PASSWORD = "tenancy_kernel_app_pw"
    }
}
