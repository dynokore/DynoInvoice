package com.dynokore.platform.tenancykernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.postgresql.ds.PGSimpleDataSource
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

/**
 * The done-when's own acceptance test: "Testcontainers tests on PostgreSQL 17
 * and 18 prove a leaked context never crosses a pooled connection", plus every
 * INV-REQ-854 behaviour CS-19 and ARC-21 spell out. Each `@ParameterizedTest`
 * invocation starts its own container on the named image — real PostgreSQL,
 * not a fake — and runs the full scenario against it (S2-05: connecting as
 * `tenancy_kernel_app`, never the container's default superuser, so every
 * assertion below is a real RLS decision, not vacuously true).
 */
@Tag("integration")
class TenancyKernelPostgresIT {
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `RLS isolation and pooled-connection cleanup hold`(image: String) {
        PostgresProbeFixture(image).use { fixture ->
            val tenantA = TenantId(UUID.randomUUID())
            val tenantB = TenantId(UUID.randomUUID())

            assertConnectionIsNeitherSuperuserNorBypassRls(fixture)
            assertFailsClosedWithNoTenantBound(fixture)
            assertFindNeverReturnsAnotherTenantsRow(fixture, tenantA, tenantB)
            assertForeignTenantWriteIsRefused(fixture, tenantA, tenantB)
            assertLeakedContextNeverCrossesAPooledConnection(fixture, tenantA, tenantB)
            assertPlatformRowsAreIsolatedLikeAnyTenant(fixture, tenantA)
        }
    }

    /** V-01 (TPL-14): the runtime role can neither read past RLS nor is exempt from it. */
    private fun assertConnectionIsNeitherSuperuserNorBypassRls(fixture: PostgresProbeFixture) {
        app(fixture).connection.use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "select (rolsuper or rolbypassrls) as escalated from pg_roles where rolname = current_user",
                    ).use { rs ->
                        assertTrue(rs.next())
                        assertFalse(rs.getBoolean("escalated"))
                    }
            }
        }
    }

    /** CS-19 "V-10": unset or blank `app.tenant_id` matches no row — fail-closed, not fail-open. */
    private fun assertFailsClosedWithNoTenantBound(fixture: PostgresProbeFixture) {
        val app = app(fixture)
        val tenant = TenantId(UUID.randomUUID())
        app.connection.use { connection ->
            connection.autoCommit = false
            val setTenant = "select set_config('app.tenant_id', '${tenant.value}', true)"
            connection.createStatement().use { it.execute(setTenant) }
            insertProbe(connection, ProbeRow(UUID.randomUUID(), tenant, "fail-closed-probe"))
            connection.commit()
        }
        // A fresh connection with nothing bound at all (no set_config call this time).
        app.connection.use { connection ->
            val rows = selectProbes(connection)
            assertTrue(rows.isEmpty(), "expected no rows visible with no tenant bound, got $rows")
        }
    }

    /**
     * INV-Q-2927 option D: rows under the reserved platform tenant sit behind the same forced RLS as
     * any tenant's (CS-19 unchanged), so neither side sees the other's rows.
     */
    private fun assertPlatformRowsAreIsolatedLikeAnyTenant(
        fixture: PostgresProbeFixture,
        tenantA: TenantId,
    ) {
        val tenants = TenantContextImpl()
        val tx = TransactionRunnerImpl(app(fixture), tenants)

        fun insert(
            tenant: TenantId,
            value: String,
        ) = tx.inTransaction { insertProbe(tx.require(), ProbeRow(UUID.randomUUID(), tenant, value)) }

        fun values() = tx.inTransaction { selectProbes(tx.require()).map { it.value } }
        tenants.bindPlatform("svc:test/job:probe") { insert(TenantId.PLATFORM, "belongs-to-platform") }
        tenants.bind(tenantA, "test-actor") { insert(tenantA, "belongs-to-a-too") }
        val seenByA = tenants.bind(tenantA, "test-actor") { values() }
        val seenByPlatform = tenants.bindPlatform("svc:test/job:probe") { values() }
        assertFalse("belongs-to-platform" in seenByA, "tenant A must never see a platform row, saw $seenByA")
        assertEquals(listOf("belongs-to-platform"), seenByPlatform)
    }

    /** TPL-14's own tenant-probe template: insert as B, read as A, expect nothing. */
    private fun assertFindNeverReturnsAnotherTenantsRow(
        fixture: PostgresProbeFixture,
        tenantA: TenantId,
        tenantB: TenantId,
    ) {
        val tenants = TenantContextImpl()
        val tx = TransactionRunnerImpl(app(fixture), tenants)

        tenants.bind(tenantB, "test-actor") {
            tx.inTransaction {
                insertProbe(tx.require(), ProbeRow(UUID.randomUUID(), tenantB, "belongs-to-b"))
            }
        }

        val visibleToA =
            tenants.bind(tenantA, "test-actor") {
                tx.inTransaction { selectProbes(tx.require()).filter { it.value == "belongs-to-b" } }
            }
        assertTrue(visibleToA.isEmpty(), "tenant A must never see tenant B's row, saw $visibleToA")
    }

    /**
     * The RLS `WITH CHECK` clause refuses a write smuggling another tenant's
     * id, as a database error, not an app-level check.
     */
    private fun assertForeignTenantWriteIsRefused(
        fixture: PostgresProbeFixture,
        tenantA: TenantId,
        tenantB: TenantId,
    ) {
        val tenants = TenantContextImpl()
        val tx = TransactionRunnerImpl(app(fixture), tenants)

        assertThrows(SQLException::class.java) {
            tenants.bind(tenantA, "test-actor") {
                tx.inTransaction {
                    // Tenant A's session, but the row claims to be tenant B's: WITH CHECK must refuse it.
                    insertProbe(tx.require(), ProbeRow(UUID.randomUUID(), tenantB, "smuggled"))
                }
            }
        }
    }

    /**
     * INV-REQ-854 behaviour 3 / the done-when's headline assertion: a pool of
     * size one, alternating tenants (ARC-21's own named CI check). Tenant A's
     * transaction runs and returns its physical connection to the one-slot
     * pool; tenant B's transaction then gets that exact same physical
     * connection back. If the tenant setting were not cleared on handback,
     * tenant B's transaction would start with tenant A's id still set instead
     * of its own.
     */
    private fun assertLeakedContextNeverCrossesAPooledConnection(
        fixture: PostgresProbeFixture,
        tenantA: TenantId,
        tenantB: TenantId,
    ) {
        val jdbcUrl = fixture.jdbcUrl()
        SingleSlotDataSource(jdbcUrl, PostgresProbeFixture.APP, PostgresProbeFixture.APP_PASSWORD).use { slot ->
            val pooled = TenantAwareDataSource(slot.dataSource)
            val tenants = TenantContextImpl()
            val tx = TransactionRunnerImpl(pooled, tenants)

            tenants.bind(tenantA, "test-actor") { tx.inTransaction { currentSetting(tx.require()) } }

            val settingSeenByB =
                tenants.bind(tenantB, "test-actor") {
                    tx.inTransaction { currentSetting(tx.require()) }
                }

            val message = "tenant B's transaction must not see tenant A's leftover session setting"
            assertEquals(tenantB.toString(), settingSeenByB, message)

            // a session-level setting (is_local false) left by a caller on a handed-out connection is cleared on handback
            pooled.connection.use { leaker ->
                leaker.createStatement().use {
                    it.execute("select set_config('app.tenant_id', '${tenantA.value}', false), set_config('app.actor', 'leaked', false)")
                }
            }
            pooled.connection.use { next ->
                assertNull(currentSettingOf(next, "app.tenant_id"), "app.tenant_id survived the handback")
                assertNull(currentSettingOf(next, "app.actor"), "app.actor survived the handback")
            }
        }
    }

    private fun currentSettingOf(
        connection: java.sql.Connection,
        name: String,
    ): String? =
        connection.createStatement().use { statement ->
            statement.executeQuery("select nullif(current_setting('$name', true), '') as t").use { rs ->
                rs.next()
                rs.getString("t")
            }
        }

    private fun currentSetting(connection: java.sql.Connection): String {
        val currentTenantSetting = "select nullif(current_setting('app.tenant_id', true), '') as t"
        return connection.createStatement().use { statement ->
            statement.executeQuery(currentTenantSetting).use { rs ->
                rs.next()
                rs.getString("t")
            }
        }
    }

    /** The runtime login's pool, wrapped as a service wires it ([TenantAwareDataSource]). */
    private fun app(fixture: PostgresProbeFixture): DataSource =
        TenantAwareDataSource(login(fixture, PostgresProbeFixture.APP, PostgresProbeFixture.APP_PASSWORD))

    private fun login(
        fixture: PostgresProbeFixture,
        user: String,
        password: String,
    ): DataSource =
        PGSimpleDataSource().apply {
            setUrl(fixture.jdbcUrl())
            this.user = user
            this.password = password
        }

    private fun insertProbe(
        connection: Connection,
        row: ProbeRow,
    ) {
        connection
            .prepareStatement(
                "insert into tenancy_kernel_test.probe (id, tenant_id, value) values (?, ?, ?)",
            ).use { statement ->
                statement.setObject(1, row.id)
                statement.setObject(2, row.tenantId.value)
                statement.setString(3, row.value)
                statement.executeUpdate()
            }
    }

    private fun selectProbes(connection: Connection): List<ProbeRow> =
        connection.createStatement().use { statement ->
            statement
                .executeQuery("select id, tenant_id, value from tenancy_kernel_test.probe order by value")
                .use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                ProbeRow(
                                    id = rs.getObject("id", UUID::class.java),
                                    tenantId = TenantId(rs.getObject("tenant_id", UUID::class.java)),
                                    value = rs.getString("value"),
                                ),
                            )
                        }
                    }
                }
        }
}
