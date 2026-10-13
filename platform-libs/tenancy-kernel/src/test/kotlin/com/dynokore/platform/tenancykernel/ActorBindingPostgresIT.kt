package com.dynokore.platform.tenancykernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/**
 * INV-T-3980 (coordinator ruling INV-Q-3997; owner rulings INV-Q-3951, INV-Q-3953;
 * INV-REQ-854 b1, b3, b5): the binding carries the actor, TransactionRunner sets
 * `app.tenant_id` and `app.actor` transaction-locally, neither survives commit or
 * rollback on a pooled connection, a nested transaction under a different binding is
 * refused, and the deprecated actor-less binding still works.
 *
 * The leak checks run on a one-slot pool WITHOUT [TenantAwareDataSource]'s RESET, so
 * they prove `set_config(..., true)` itself, then again with it.
 */
@Tag("integration")
class ActorBindingPostgresIT {
    private val tenantA = TenantId(UUID.randomUUID())
    private val tenantB = TenantId(UUID.randomUUID())

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `actor binding, no leak to the next borrower, nested mismatch refused, actor-less path works`(image: String) {
        PostgresProbeFixture(image).use { fixture ->
            fixture.createProbeTable(fixture.ownerDataSource())
            SingleSlotDataSource(fixture.jdbcUrl(), "tenancy_kernel_app", "tenancy_kernel_app_pw").use { slot ->
                assertBothSettingsSetFromTheBinding(slot.dataSource)
                assertNothingSurvivesCommitOrRollback(slot.dataSource)
                assertNothingSurvivesCommitOrRollback(TenantAwareDataSource(slot.dataSource))
                assertNestedMismatchIsRefusedBeforeAnyStatement(slot.dataSource)
                assertDeprecatedActorlessPathStillWorks(slot.dataSource)
            }
        }
    }

    private fun assertBothSettingsSetFromTheBinding(pool: DataSource) {
        val tenants = TenantContextImpl()
        val tx = TransactionRunnerImpl(pool, tenants)
        val user = tenants.bind(tenantA, "user-a") { tx.inTransaction { settings(tx.require()) } }
        assertEquals(Settings(tenantA.toString(), "user-a"), user)
        val platform = tenants.bindPlatform("svc:test/job:probe") { tx.inTransaction { settings(tx.require()) } }
        assertEquals(Settings(TenantId.PLATFORM.toString(), "svc:test/job:probe"), platform)
    }

    // INV-T-3999 removes this call
    @Suppress("DEPRECATION")
    private fun assertNothingSurvivesCommitOrRollback(pool: DataSource) {
        val tenants = TenantContextImpl()
        val tx = TransactionRunnerImpl(pool, tenants)

        tenants.bind(tenantA, "user-a") { tx.inTransaction { insert(tx.require(), tenantA, "committed") } }
        assertEquals(Settings(null, null), nextBorrowerSees(pool), "after commit")

        assertThrows(IllegalStateException::class.java) {
            tenants.bind(tenantB, "user-b") { tx.inTransaction { error("rolled back") } }
        }
        assertEquals(Settings(null, null), nextBorrowerSees(pool), "after rollback")

        // The next borrower through the runner sees only its own binding, never the previous one.
        tenants.bind(tenantA, "user-a") { tx.inTransaction { } }
        val next = tenants.bind(tenantB) { tx.inTransaction { settings(tx.require()) } }
        assertEquals(Settings(tenantB.toString(), null), next)
    }

    // INV-T-3999 removes this call
    @Suppress("DEPRECATION")
    private fun assertNestedMismatchIsRefusedBeforeAnyStatement(pool: DataSource) {
        val tenants = TenantContextImpl()
        val tx = TransactionRunnerImpl(pool, tenants)
        val mismatches: List<(() -> Unit) -> Unit> =
            listOf(
                { block -> tenants.bind(tenantB, "user-a", block) }, // other tenant
                { block -> tenants.bind(tenantA, "user-b", block) }, // other actor
                { block -> tenants.bind(tenantA, block) }, // actor-less under an actor-bound transaction
                { block -> tenants.bindPlatform("user-a", block) }, // platform under a tenant's transaction
            )
        val marker = "outer-survives-${UUID.randomUUID()}"
        val seenAfter =
            tenants.bind(tenantA, "user-a") {
                tx.inTransaction {
                    for (bindOther in mismatches) {
                        var ran = false
                        val refused =
                            assertThrows(IllegalStateException::class.java) {
                                bindOther { tx.inTransaction { ran = true } }
                            }
                        assertTrue("INV-Q-3997" in refused.message.orEmpty(), refused.message)
                        assertFalse(ran, "a refused nested transaction must not run its block")
                    }
                    // Same binding joins; the outer transaction is intact and still bound to A and user-a.
                    tenants.bind(tenantA, "user-a") { tx.inTransaction { insert(tx.require(), tenantA, marker) } }
                    settings(tx.require())
                }
            }
        assertEquals(Settings(tenantA.toString(), "user-a"), seenAfter)
        val committed =
            tenants.bind(tenantA, "user-a") { tx.inTransaction { ProbeTable.selectAll(tx.require()).map { it.value } } }
        assertTrue(marker in committed, "the outer transaction commits after the refusals, saw $committed")
    }

    // INV-T-3999 removes this call
    @Suppress("DEPRECATION")
    private fun assertDeprecatedActorlessPathStillWorks(pool: DataSource) {
        val tenants = TenantContextImpl()
        val tx = TransactionRunnerImpl(pool, tenants)
        val nested =
            tenants.bind(tenantB) {
                tx.inTransaction { tenants.bind(tenantB) { tx.inTransaction { settings(tx.require()) } } }
            }
        assertEquals(Settings(tenantB.toString(), null), nested)
        val platform = tenants.bindPlatform { tx.inTransaction { settings(tx.require()) } }
        assertEquals(Settings(TenantId.PLATFORM.toString(), null), platform)
        assertNull(nextBorrowerSees(pool).actor)
    }

    private fun insert(
        connection: Connection,
        tenant: TenantId,
        value: String,
    ) = ProbeTable.insert(connection, ProbeRow(UUID.randomUUID(), tenant, value))

    private data class Settings(
        val tenant: String?,
        val actor: String?,
    )

    /** A plain borrow outside the runner: what the next user of the pooled connection would inherit. */
    private fun nextBorrowerSees(pool: DataSource): Settings = pool.connection.use { settings(it) }

    private fun settings(connection: Connection): Settings =
        connection.createStatement().use { statement ->
            statement
                .executeQuery(
                    "select nullif(current_setting('app.tenant_id', true), '') as t, " +
                        "nullif(current_setting('app.actor', true), '') as a",
                ).use { rs ->
                    rs.next()
                    Settings(rs.getString("t"), rs.getString("a"))
                }
        }
}
