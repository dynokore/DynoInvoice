package com.dynokore.platform.tenancykernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/**
 * INV-T-3982 (coordinator ruling INV-Q-4487; INV-REQ-854 b3): a [ConnectionLabel] adds a transaction-local
 * `set_config('application_name', ?, true)` to the binding statement. pg_stat_activity shows the label while the
 * transaction runs, and the same pooled connection shows its own name again after commit and after rollback. Without
 * a label (or when it gives none) the connection's name is never touched.
 */
@Tag("integration")
class ApplicationNameLabelPostgresIT {
    private val tenant = TenantId(UUID.randomUUID())

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `the label is the application_name of the transaction only`(image: String) {
        PostgresProbeFixture(image).use { fixture ->
            SingleSlotDataSource(fixture.jdbcUrl(), "tenancy_kernel_app", "tenancy_kernel_app_pw").use { slot ->
                assertLabelEndsWithTheTransaction(slot.dataSource)
                assertLabelEndsWithTheTransaction(TenantAwareDataSource(slot.dataSource))
                assertNoLabelLeavesTheNameAlone(slot.dataSource)
            }
        }
    }

    private fun assertLabelEndsWithTheTransaction(pool: DataSource) {
        val own = pool.connection.use { it.idleName() }
        val tenants = TenantContextImpl()
        val tx = TransactionRunnerImpl(pool, tenants) { "submission-svc:background" }

        val during = tenants.bind(tenant, "user-a") { tx.inTransaction { tx.require().applicationName() } }
        assertEquals("submission-svc:background", during, "during the transaction")
        assertEquals(own, pool.connection.use { it.idleName() }, "after commit")

        assertThrows(IllegalStateException::class.java) {
            tenants.bind(tenant, "user-a") {
                tx.inTransaction {
                    assertEquals("submission-svc:background", tx.require().applicationName())
                    error("rolled back")
                }
            }
        }
        assertEquals(own, pool.connection.use { it.idleName() }, "after rollback")
        assertNotEquals("submission-svc:background", own)
    }

    private fun assertNoLabelLeavesTheNameAlone(pool: DataSource) {
        val own = pool.connection.use { it.idleName() }
        val tenants = TenantContextImpl()
        listOf(TransactionRunnerImpl(pool, tenants), TransactionRunnerImpl(pool, tenants) { null }).forEach { tx ->
            assertEquals(own, tenants.bind(tenant, "user-a") { tx.inTransaction { tx.require().applicationName() } })
        }
    }

    /**
     * The name outside any transaction: the one-slot pool keeps the runner's autoCommit(false), so autoCommit is
     * restored first (as HikariCP does on return); otherwise this read would open a transaction whose pg_stat_activity
     * snapshot the next transaction would see.
     */
    private fun Connection.idleName(): String {
        autoCommit = true
        return applicationName()
    }

    /** This backend's application_name as pg_stat_activity shows it. */
    private fun Connection.applicationName(): String = createStatement().use { statement ->
        statement.executeQuery("select application_name from pg_stat_activity where pid = pg_backend_pid()").use {
            it.next()
            it.getString(1)
        }
    }
}
