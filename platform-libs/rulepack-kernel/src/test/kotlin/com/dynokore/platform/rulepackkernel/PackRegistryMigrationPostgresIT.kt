package com.dynokore.platform.rulepackkernel

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import java.sql.SQLException
import java.sql.Types
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/** Testcontainers needs a concrete subclass for its fluent `self()` typing (Kotlin, unlike Java, has no raw types). */
private class Postgres(image: DockerImageName) : PostgreSQLContainer<Postgres>(image)

/**
 * Proves [PackRegistryMigration]'s row-level-security policy against a real
 * PostgreSQL (review should-fix, then escalated must-fix, on this MR, CS-19):
 * a text assertion on the generated SQL cannot catch a policy that is
 * well-formed but wrong, as both the original symmetric `WITH CHECK` and the
 * later "no tenant bound treated as the platform identity" version were.
 * Connects as the same kind of INSERT/SELECT-only, `NOSUPERUSER`/`NOBYPASSRLS`
 * role a real pack holder's runtime uses, so every assertion below is a real
 * RLS decision, never an app-level check.
 */
@Tag("integration")
class PackRegistryMigrationPostgresIT {
    @Test
    fun `RLS isolates tenants, admits the platform operator, and fails closed with no identity bound`() {
        val container = Postgres(DockerImageName.parse("postgres:17"))
        container.start()
        try {
            val owner = dataSourceFor(container, container.username, container.password)
            owner.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE SCHEMA rulepack_test")
                    statement.execute(PackRegistryMigration.ddl(schema = "rulepack_test", table = "rule_pack_version"))
                    statement.execute(
                        "CREATE ROLE rulepack_kernel_app LOGIN PASSWORD 'rulepack_kernel_app_pw' " +
                            "NOSUPERUSER NOBYPASSRLS",
                    )
                    statement.execute("GRANT USAGE ON SCHEMA rulepack_test TO rulepack_kernel_app")
                    statement.execute("GRANT INSERT, SELECT ON rulepack_test.rule_pack_version TO rulepack_kernel_app")
                }
            }

            val app = dataSourceFor(container, "rulepack_kernel_app", "rulepack_kernel_app_pw")
            val tenantA = UUID.randomUUID()
            val tenantB = UUID.randomUUID()

            assertTenantSeesOwnRowNeverAnothers(app, tenantA, tenantB)
            assertTenantCannotSmuggleAPlatformWideRow(app, tenantA)
            assertPlatformOperatorCanInsertAndReadAPlatformWideRow(app)
            assertNoIdentityBoundFailsClosedOnReadAndWrite(app, tenantA)
        } finally {
            container.stop()
        }
    }

    /** A tenant can insert its own row; tenant A cannot read tenant B's row. */
    private fun assertTenantSeesOwnRowNeverAnothers(
        app: DataSource,
        tenantA: UUID,
        tenantB: UUID,
    ) {
        insert(app, tenant = tenantA, rowTenant = tenantA, packId = "a-own-pack")
        insert(app, tenant = tenantB, rowTenant = tenantB, packId = "b-own-pack")

        val visibleToA = selectPackIds(app, tenant = tenantA)
        assertTrue(visibleToA.contains("a-own-pack"), "tenant A must see its own row, saw $visibleToA")
        assertFalse(visibleToA.contains("b-own-pack"), "tenant A must never see tenant B's row, saw $visibleToA")
    }

    /**
     * `WITH CHECK` must refuse a tenant-bound session inserting a NULL-tenant
     * row as a database error, not an app-level check — "no tenant bound" is
     * not by itself the platform operator (the must-fix this test guards):
     * only an explicit `app.platform_operator = 'true'` identity may.
     */
    private fun assertTenantCannotSmuggleAPlatformWideRow(
        app: DataSource,
        tenantA: UUID,
    ) {
        assertThrows(SQLException::class.java) {
            insert(app, tenant = tenantA, rowTenant = null, packId = "smuggled-platform-wide")
        }
    }

    /**
     * The platform operator's own identity (INV-REQ-1343) can insert and read
     * a platform-wide row — the fix must not merely refuse everyone.
     */
    private fun assertPlatformOperatorCanInsertAndReadAPlatformWideRow(app: DataSource) {
        insert(app, platformOperator = true, rowTenant = null, packId = "operator-platform-wide")
        val visibleToOperator = selectPackIds(app, platformOperator = true)
        assertTrue(
            visibleToOperator.contains("operator-platform-wide"),
            "the platform operator must see the platform-wide row it inserted, saw $visibleToOperator",
        )
    }

    /**
     * A session with no identity bound at all — no `set_config` call, ever —
     * must see nothing (fail-closed on read, CS-19, the same guarantee
     * `TenancyKernelPostgresIT.assertFailsClosedWithNoTenantBound` proves for
     * tenancy-kernel's own tables) and must not be able to insert anything,
     * including a NULL-tenant row (fail-closed on write too — the must-fix
     * this test guards: absence must never be read as the platform identity).
     */
    private fun assertNoIdentityBoundFailsClosedOnReadAndWrite(
        app: DataSource,
        tenantA: UUID,
    ) {
        val visible = selectPackIds(app)
        assertTrue(visible.isEmpty(), "expected no rows visible with no identity bound, got $visible")
        assertThrows(SQLException::class.java) {
            insert(app, rowTenant = null, packId = "unbound-platform-wide")
        }
        assertThrows(SQLException::class.java) {
            insert(app, rowTenant = tenantA, packId = "unbound-claiming-tenant-a")
        }
    }

    private val insertSql =
        """
        INSERT INTO rulepack_test.rule_pack_version
            (id, pack_id, pack_type, version, tenant_id, effective_from, min_engine_version,
             manifest, checksums, signature, contents, trusted_anchor)
        VALUES (?, ?, 'Scope', '1.0.0', ?, ?, '1.0.0', ?::jsonb, ?::jsonb, ?, ?::jsonb, 'test-anchor')
        """.trimIndent()

    /**
     * Inserts one row through a session with [tenant] bound as `app.tenant_id`
     * and/or [platformOperator] bound as `app.platform_operator` (neither, by
     * default — no identity at all), the row itself carrying [rowTenant].
     */
    private fun insert(
        dataSource: DataSource,
        tenant: UUID? = null,
        platformOperator: Boolean = false,
        rowTenant: UUID?,
        packId: String,
    ) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            bindIdentity(connection, tenant, platformOperator)
            connection.prepareStatement(insertSql).use { ps ->
                ps.setObject(1, UUID.randomUUID())
                ps.setString(2, packId)
                if (rowTenant == null) ps.setNull(3, Types.OTHER) else ps.setObject(3, rowTenant)
                ps.setObject(4, LocalDate.now())
                ps.setString(5, "{}")
                ps.setString(6, "{}")
                ps.setBytes(7, byteArrayOf(1, 2, 3))
                ps.setString(8, "{}")
                ps.executeUpdate()
            }
            connection.commit()
        }
    }

    /**
     * Reads every visible `pack_id` through a session with [tenant] and/or
     * [platformOperator] bound (neither, by default — no identity at all).
     */
    private fun selectPackIds(
        dataSource: DataSource,
        tenant: UUID? = null,
        platformOperator: Boolean = false,
    ): List<String> {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            bindIdentity(connection, tenant, platformOperator)
            val values =
                connection.createStatement().use { statement ->
                    statement.executeQuery("select pack_id from rulepack_test.rule_pack_version").use { rs ->
                        generateSequence { if (rs.next()) rs.getString("pack_id") else null }.toList()
                    }
                }
            connection.commit()
            return values
        }
    }

    /** Binds exactly [tenant] and/or [platformOperator] on [connection] — no call at all for either that is absent. */
    private fun bindIdentity(
        connection: Connection,
        tenant: UUID?,
        platformOperator: Boolean,
    ) {
        connection.createStatement().use { statement ->
            if (tenant != null) statement.execute("select set_config('app.tenant_id', '$tenant', true)")
            if (platformOperator) statement.execute("select set_config('app.platform_operator', 'true', true)")
        }
    }

    private fun dataSourceFor(
        container: Postgres,
        user: String,
        password: String,
    ): DataSource = PGSimpleDataSource().apply {
        setUrl(container.jdbcUrl)
        this.user = user
        this.password = password
    }
}
