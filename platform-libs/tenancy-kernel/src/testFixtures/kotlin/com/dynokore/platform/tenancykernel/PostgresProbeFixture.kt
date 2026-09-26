package com.dynokore.platform.tenancykernel

import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import javax.sql.DataSource

/** Testcontainers needs a concrete subclass for its fluent `self()` typing (Kotlin, unlike Java, has no raw types). */
private class Postgres(image: DockerImageName) : PostgreSQLContainer<Postgres>(image)

/**
 * Isolation-test fixtures (done-when, §9.2/§13.3): starts a real PostgreSQL
 * container running the two roles [tenancy-kernel-test-roles.sql] creates, and
 * hands out ready-to-use [DataSource]s for each — so a test proves RLS against
 * the actual database CS-19 targets, not a fake.
 *
 * One instance per test class, started once and stopped in [close]; a test
 * class typically wants one per PostgreSQL version under test
 * (`@ParameterizedTest` over `postgres:17` and `postgres:18`, the done-when's
 * own matrix), so this is a value a test constructs itself rather than a
 * shared JUnit extension singleton.
 */
class PostgresProbeFixture(image: String) : AutoCloseable {
    private val container: Postgres = Postgres(DockerImageName.parse(image)).withInitScript("db/tenancy-kernel-test-roles.sql")

    init {
        container.start()
    }

    /** The JDBC URL of the running container, for a test that builds its own [DataSource] (e.g. [SingleSlotDataSource]). */
    fun jdbcUrl(): String = container.jdbcUrl

    /** The migration/owner role — DDL only, used to create the probe table and apply [TenantRlsMigration]. */
    fun ownerDataSource(): DataSource = dataSourceFor("tenancy_kernel_owner", "tenancy_kernel_owner_pw")

    /**
     * The runtime application role — [TenantAwareDataSource]-wrapped, `NOSUPERUSER`,
     * `NOBYPASSRLS`, owns no table (S2-05): every query through this DataSource is
     * subject to the same RLS a real service's connection would be.
     */
    fun appDataSource(): DataSource = TenantAwareDataSource(dataSourceFor("tenancy_kernel_app", "tenancy_kernel_app_pw"))

    /** The one probe table this module's own tests exercise the RLS mechanism against. */
    fun createProbeTable(owner: DataSource) {
        owner.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE tenancy_kernel_test.probe (
                        id        uuid PRIMARY KEY,
                        tenant_id uuid NOT NULL,
                        value     text NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.execute(TenantRlsMigration.ddl(schema = "tenancy_kernel_test", table = "probe"))
            }
        }
    }

    override fun close() {
        container.stop()
    }

    private fun dataSourceFor(
        user: String,
        password: String,
    ): DataSource = PGSimpleDataSource().apply {
        setUrl(container.jdbcUrl)
        this.user = user
        this.password = password
    }
}
