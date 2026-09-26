package com.dynokore.platform.rulepackkernel

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.SQLException
import java.sql.Types
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/** Testcontainers needs a concrete subclass for its fluent `self()` typing (Kotlin, unlike Java, has no raw types). */
private class Postgres(image: DockerImageName) : PostgreSQLContainer<Postgres>(image)

/**
 * Proves [PackRegistryMigration]'s row-level-security policy against a real
 * PostgreSQL (review should-fix on this MR, CS-19): a text assertion on the
 * generated SQL cannot catch a policy that is well-formed but wrong, as the
 * original symmetric `WITH CHECK` was. Connects as the same kind of
 * INSERT/SELECT-only, `NOSUPERUSER`/`NOBYPASSRLS` role a real pack holder's
 * runtime uses (CS-19), so every assertion below is a real RLS decision.
 */
@Tag("integration")
class PackRegistryMigrationPostgresIT {
    @Test
    fun `RLS isolates tenants on read and refuses a smuggled platform-wide row on write`() {
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

            // Tenant can insert its own row.
            insertAsTenant(app, boundTenant = tenantA, rowTenant = tenantA, packId = "a-own-pack")
            insertAsTenant(app, boundTenant = tenantB, rowTenant = tenantB, packId = "b-own-pack")

            // Tenant A cannot read tenant B's row.
            val visibleToA = selectPackIdsAsTenant(app, tenantA)
            assertTrue(visibleToA.contains("a-own-pack"), "tenant A must see its own row, saw $visibleToA")
            assertFalse(visibleToA.contains("b-own-pack"), "tenant A must never see tenant B's row, saw $visibleToA")

            // Tenant A cannot insert a NULL-tenant (platform-wide) row: WITH CHECK
            // must refuse it as a database error, not an app-level check — only
            // the platform/maintenance identity (no tenant bound at all) may.
            assertThrows(SQLException::class.java) {
                insertAsTenant(app, boundTenant = tenantA, rowTenant = null, packId = "smuggled-platform-wide")
            }
        } finally {
            container.stop()
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
     * Inserts one row through a session with [boundTenant] set as `app.tenant_id`,
     * the row itself carrying [rowTenant].
     */
    private fun insertAsTenant(
        dataSource: DataSource,
        boundTenant: UUID,
        rowTenant: UUID?,
        packId: String,
    ) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            connection.createStatement().use { it.execute("select set_config('app.tenant_id', '$boundTenant', true)") }
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

    /** Reads every visible `pack_id` through a session with [tenant] bound as `app.tenant_id`. */
    private fun selectPackIdsAsTenant(
        dataSource: DataSource,
        tenant: UUID,
    ): List<String> {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            connection.createStatement().use { it.execute("select set_config('app.tenant_id', '$tenant', true)") }
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
