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
import java.util.UUID
import javax.sql.DataSource

/**
 * INV-T-3980 (coordinator ruling INV-Q-3997; owner rulings INV-Q-3951, INV-Q-3953;
 * INV-REQ-854 b1, b3, b5): the binding carries the actor, TransactionRunner sets
 * `app.tenant_id` and `app.actor` transaction-locally, neither survives commit or
 * rollback on a pooled connection, a nested transaction under a different binding is
 * refused, and a transaction with no actor is refused before any SQL.
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
    fun `actor binding, no leak, nested mismatch refused, no-actor transaction refused`(image: String) {
        PostgresProbeFixture(image).use { fixture ->
            val app = PostgresProbeFixture.APP
            SingleSlotDataSource(fixture.jdbcUrl(), app, PostgresProbeFixture.APP_PASSWORD).use { slot ->
                assertBothSettingsSetFromTheBinding(slot.dataSource)
                assertNothingSurvivesCommitOrRollback(slot.dataSource)
                assertNothingSurvivesCommitOrRollback(TenantAwareDataSource(slot.dataSource))
                assertNestedMismatchIsRefusedBeforeAnyStatement(slot.dataSource)
                assertTransactionWithNoActorIsRefusedBeforeAnySql(slot)
            }
        }
    }

    private fun assertBothSettingsSetFromTheBinding(pool: DataSource) {
        val tenants = TenantContextImpl()
        val tx = TransactionRunnerImpl(pool, tenants, null, SignedBinding.keys())
        val user = tenants.bind(tenantA, "user-a") { tx.inTransaction { settings(tx.require()) } }
        assertEquals(Settings(tenantA.toString(), "user-a"), user)
        val platform = tenants.bindPlatform("svc:test/job:probe") { tx.inTransaction { settings(tx.require()) } }
        assertEquals(Settings(TenantId.PLATFORM.toString(), "svc:test/job:probe"), platform)
    }

    private fun assertNothingSurvivesCommitOrRollback(pool: DataSource) {
        val tenants = TenantContextImpl()
        val tx = TransactionRunnerImpl(pool, tenants, null, SignedBinding.keys())

        tenants.bind(tenantA, "user-a") { tx.inTransaction { insert(tx.require(), tenantA, "committed") } }
        assertEquals(Settings(null, null), nextBorrowerSees(pool), "after commit")

        assertThrows(IllegalStateException::class.java) {
            tenants.bind(tenantB, "user-b") { tx.inTransaction { error("rolled back") } }
        }
        assertEquals(Settings(null, null), nextBorrowerSees(pool), "after rollback")

        // The next borrower through the runner sees only its own binding, never the previous one.
        tenants.bind(tenantA, "user-a") { tx.inTransaction { } }
        val next = tenants.bind(tenantB, "user-b") { tx.inTransaction { settings(tx.require()) } }
        assertEquals(Settings(tenantB.toString(), "user-b"), next)
    }

    private fun assertNestedMismatchIsRefusedBeforeAnyStatement(pool: DataSource) {
        val tenants = TenantContextImpl()
        val tx = TransactionRunnerImpl(pool, tenants, null, SignedBinding.keys())
        val mismatches: List<(() -> Unit) -> Unit> =
            listOf(
                { block -> tenants.bind(tenantB, "user-a", block) }, // other tenant
                { block -> tenants.bind(tenantA, "user-b", block) }, // other actor
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
            tenants.bind(tenantA, "user-a") { tx.inTransaction { selectProbeValues(tx.require()) } }
        assertTrue(marker in committed, "the outer transaction commits after the refusals, saw $committed")
    }

    private fun assertTransactionWithNoActorIsRefusedBeforeAnySql(slot: SingleSlotDataSource) {
        val counting = slot.dataSource
        val before = slot.checkouts
        val real = TenantContextImpl()
        // Nothing bound: refused before a connection is even taken.
        val unbound = TransactionRunnerImpl(counting, real, null, SignedBinding.keys())
        assertThrows(IllegalStateException::class.java) { unbound.inTransaction { } }
        // A foreign TenantContext answering no actor, a blank one or one with a control character: refused before SQL.
        listOf(null, "", " ", "\t", "\u00A0", "\uFEFF", "a\tb", "x\u0000", "x\u001B[0m").forEach { answer ->
            val foreign =
                object : TenantContext {
                    override fun current(): TenantId = tenantA

                    override fun currentActor(): String? = answer

                    override fun <T> bind(
                        tenant: TenantId,
                        actor: String,
                        block: () -> T,
                    ): T = block()
                }
            var ran = false
            val refused =
                assertThrows(IllegalStateException::class.java) {
                    TransactionRunnerImpl(counting, foreign, null, SignedBinding.keys()).inTransaction { ran = true }
                }
            assertTrue("actor" in refused.message.orEmpty(), "actor $answer: ${refused.message}")
            assertFalse(ran)
        }
        assertEquals(before, slot.checkouts, "no connection was opened")
    }

    private fun insert(
        connection: Connection,
        tenant: TenantId,
        value: String,
    ) {
        connection
            .prepareStatement("insert into tenancy_kernel_test.probe (id, tenant_id, value) values (?, ?, ?)")
            .use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, tenant.value)
                statement.setString(3, value)
                statement.executeUpdate()
            }
    }

    private fun selectProbeValues(connection: Connection): List<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery("select value from tenancy_kernel_test.probe order by value").use { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }
        }

    private data class Settings(
        val tenant: String?,
        val actor: String?,
    )

    /** A plain borrow outside the runner: what the next user of the pooled connection would inherit. */
    private fun nextBorrowerSees(pool: DataSource): Settings = pool.connection.use { settings(it) }

    private fun settings(connection: Connection): Settings =
        connection
            .prepareStatement(
                "select nullif(current_setting(?, true), '') as t, nullif(current_setting(?, true), '') as a",
            ).use {
                it.setString(1, "app.tenant_id")
                it.setString(2, "app.actor")
                it.executeQuery().use { rs ->
                    rs.next()
                    Settings(rs.getString("t"), rs.getString("a"))
                }
            }
}
