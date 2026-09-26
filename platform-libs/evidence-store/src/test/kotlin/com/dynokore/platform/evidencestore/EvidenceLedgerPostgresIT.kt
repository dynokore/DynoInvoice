package com.dynokore.platform.evidencestore

import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * The done-when's own acceptance test: hash-chained append-only evidence,
 * refused in-place update by both DB grants and a trigger, indexed retrieval
 * scoped to entity and period, and tenant isolation — proved against real
 * PostgreSQL 17 and 18 (STK-39), not a fake.
 */
@Tag("integration")
class EvidenceLedgerPostgresIT {
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `append-only, chained, tenant-isolated evidence with a working legal hold`(image: String) {
        EvidenceStorePostgresFixture(image).use { fixture ->
            val owner = fixture.ownerDataSource()
            fixture.createTables(owner)

            assertAppendChainsAndIsRetrievable(fixture)
            assertAppRoleCannotUpdateOrDelete(fixture)
            assertTriggerRefusesMutationEvenForTheOwnerRole(owner)
            assertRlsIsolatesTenants(fixture)
            assertTamperedRowIsDetectedByVerifyChain(fixture, owner)
            assertLegalHoldPlaceAndRelease(fixture)
        }
    }

    private fun assertAppendChainsAndIsRetrievable(fixture: EvidenceStorePostgresFixture) {
        val tenant = TenantId(UUID.randomUUID())
        val entity = EntityRef("entity-1")
        val period = PeriodRef("2026-Q1")

        val recorded = runInTransaction(fixture, tenant) { ledger ->
            listOf(
                ledger.append(tenant, entity, period, EvidenceType.PAYLOAD, "first".toByteArray()),
                ledger.append(tenant, entity, period, EvidenceType.ACKNOWLEDGEMENT, "second".toByteArray()),
                ledger.append(tenant, entity, period, EvidenceType.DECISION_TRACE, "third".toByteArray()),
            )
        }

        assertEquals(listOf(0L, 1L, 2L), recorded.map { it.sequence })
        assertEquals(null, recorded[0].previousHash)
        assertEquals(recorded[0].contentHash, recorded[1].previousHash)
        assertEquals(recorded[1].contentHash, recorded[2].previousHash)

        val found = runInTransaction(fixture, tenant) { ledger -> ledger.findByEntityAndPeriod(tenant, entity, period) }
        assertEquals(recorded, found)

        val exported =
            runInTransaction(fixture, tenant) { ledger -> ledger.bulkExport(tenant, entity, period).toList() }
        assertEquals(recorded, exported)

        val verified = runInTransaction(fixture, tenant) { ledger -> ledger.verifyChain(tenant, entity, period) }
        assertEquals(ChainVerificationResult.Valid(3), verified)
    }

    /** CS-22: "DB grants: no UPDATE, DELETE or TRUNCATE on those tables for the service role". */
    private fun assertAppRoleCannotUpdateOrDelete(fixture: EvidenceStorePostgresFixture) {
        val updateSql = "update ${qualifiedTable()} set content = content where true"
        val deleteSql = "delete from ${qualifiedTable()} where true"
        fixture.appDataSource().connection.use { connection ->
            assertThrows(SQLException::class.java) {
                connection.createStatement().use { it.execute(updateSql) }
            }
        }
        fixture.appDataSource().connection.use { connection ->
            assertThrows(SQLException::class.java) {
                connection.createStatement().use { it.execute(deleteSql) }
            }
        }
    }

    /**
     * Done-when: "in-place update refused by ... a trigger" — the second,
     * independent control. The owner role runs migrations and so still holds
     * UPDATE/DELETE on its own table (unlike the app role); the trigger is
     * what still refuses it, exactly the way `FORCE ROW LEVEL SECURITY` binds
     * the owner too for tenancy (TPL-12) — which is also why this needs its
     * own row inserted under an explicitly bound tenant first: `FORCE ROW
     * LEVEL SECURITY` binds the owner's own `UPDATE`/`DELETE` to the same
     * predicate, so with no tenant set at all the statement would affect zero
     * rows and the trigger would never fire, proving nothing either way.
     */
    private fun assertTriggerRefusesMutationEvenForTheOwnerRole(owner: DataSource) {
        val tenant = TenantId(UUID.randomUUID())
        val updateSql = "update ${qualifiedTable()} set content = content where true"
        val deleteSql = "delete from ${qualifiedTable()} where true"
        assertTriggerRefuses(owner, tenant, updateSql, "append-only")
        assertTriggerRefuses(owner, tenant, deleteSql, "append-only")
    }

    private fun assertTriggerRefuses(
        owner: DataSource,
        tenant: TenantId,
        statementSql: String,
        expectedMessageFragment: String,
    ) {
        owner.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement("select set_config('app.tenant_id', ?, true)").use { statement ->
                    statement.setString(1, tenant.value.toString())
                    statement.execute()
                }
                val insertSql =
                    "insert into ${qualifiedTable()} " +
                        "(id, tenant_id, entity_ref, period_ref, seq, evidence_type, " +
                        "content, content_hash, previous_hash, recorded_at) " +
                        "values (?, ?, 'trigger-entity', 'trigger-period', 0, 'PAYLOAD', ?, ?, null, now())"
                connection.prepareStatement(insertSql).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, tenant.value)
                    statement.setBytes(3, "seed".toByteArray())
                    statement.setString(4, "1".repeat(64))
                    statement.executeUpdate()
                }
                val failure =
                    assertThrows(SQLException::class.java) {
                        connection.createStatement().use { it.execute(statementSql) }
                    }
                assertTrue(
                    failure.message?.contains(expectedMessageFragment) == true,
                    "expected the CS-22 trigger message, was: ${failure.message}",
                )
            } finally {
                connection.rollback()
            }
        }
    }

    private fun assertRlsIsolatesTenants(fixture: EvidenceStorePostgresFixture) {
        val tenantA = TenantId(UUID.randomUUID())
        val tenantB = TenantId(UUID.randomUUID())
        val entity = EntityRef("shared-entity")
        val period = PeriodRef("2026-Q2")

        runInTransaction(fixture, tenantB) { ledger ->
            ledger.append(tenantB, entity, period, EvidenceType.AUDIT_LOG, "b-only".toByteArray())
        }

        val visibleToA =
            runInTransaction(fixture, tenantA) { ledger -> ledger.findByEntityAndPeriod(tenantA, entity, period) }
        assertTrue(visibleToA.isEmpty(), "tenant A must never see tenant B's evidence, saw $visibleToA")
    }

    /**
     * With the trigger structurally in the way of an UPDATE, tampering is
     * modelled the way it would really happen — a bad row landing in the
     * table some other way (a restore, a bug in a future writer) — rather
     * than by defeating the guarantee the previous two assertions just
     * proved.
     */
    private fun assertTamperedRowIsDetectedByVerifyChain(
        fixture: EvidenceStorePostgresFixture,
        owner: DataSource,
    ) {
        val tenant = TenantId(UUID.randomUUID())
        val entity = EntityRef("tamper-entity")
        val period = PeriodRef("2026-Q3")

        runInTransaction(fixture, tenant) { ledger ->
            ledger.append(tenant, entity, period, EvidenceType.PAYLOAD, "genuine".toByteArray())
        }

        owner.connection.use { connection ->
            // WITH CHECK applies to this INSERT too (RLS, CS-19): the tenant
            // must be bound, in this same transaction, for the row to be
            // accepted at all.
            connection.autoCommit = false
            connection.prepareStatement("select set_config('app.tenant_id', ?, true)").use { statement ->
                statement.setString(1, tenant.value.toString())
                statement.execute()
            }
            val insertSql =
                "insert into ${qualifiedTable()} " +
                    "(id, tenant_id, entity_ref, period_ref, seq, evidence_type, " +
                    "content, content_hash, previous_hash, recorded_at) " +
                    "values (?, ?, ?, ?, 1, 'PAYLOAD', ?, ?, ?, ?)"
            connection.prepareStatement(insertSql).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, tenant.value)
                statement.setString(3, entity.value)
                statement.setString(4, period.value)
                statement.setBytes(5, "tampered".toByteArray())
                statement.setString(6, "0".repeat(64)) // an arbitrary hash, not the real chain link
                statement.setString(7, null)
                statement.setTimestamp(8, Timestamp.from(Instant.now()))
                statement.executeUpdate()
            }
            connection.commit()
        }

        val result = runInTransaction(fixture, tenant) { ledger -> ledger.verifyChain(tenant, entity, period) }
        assertTrue(result is ChainVerificationResult.Broken, "expected Broken, was $result")
        assertEquals(1L, (result as ChainVerificationResult.Broken).atSequence)
    }

    private fun assertLegalHoldPlaceAndRelease(fixture: EvidenceStorePostgresFixture) {
        val tenant = TenantId(UUID.randomUUID())
        val entity = EntityRef("hold-entity")
        val period = PeriodRef("2026-Q4")
        val tenants = TenantContextImpl()
        val tx = TransactionRunnerImpl(fixture.appDataSource(), tenants)
        val holdTable = EvidenceStorePostgresFixture.LEGAL_HOLD_TABLE
        val registry = JdbcLegalHoldRegistry(tx, EvidenceStorePostgresFixture.SCHEMA, holdTable)

        tenants.bind(tenant) {
            tx.inTransaction { assertFalse(registry.isHeld(tenant, entity, period)) }
            tx.inTransaction { registry.place(tenant, entity, period, LegalHold("under audit", Instant.now())) }
            tx.inTransaction { assertTrue(registry.isHeld(tenant, entity, period)) }
            tx.inTransaction { registry.release(tenant, entity, period) }
            tx.inTransaction { assertFalse(registry.isHeld(tenant, entity, period)) }
        }
    }

    private fun qualifiedTable(): String {
        val schema = EvidenceStorePostgresFixture.SCHEMA
        val table = EvidenceStorePostgresFixture.EVIDENCE_TABLE
        return "$schema.$table"
    }

    private fun <T> runInTransaction(
        fixture: EvidenceStorePostgresFixture,
        tenant: TenantId,
        block: (EvidenceLedger) -> T,
    ): T {
        val tenants = TenantContextImpl()
        val tx = TransactionRunnerImpl(fixture.appDataSource(), tenants)
        val ledger =
            JdbcEvidenceLedger(tx, EvidenceStorePostgresFixture.SCHEMA, EvidenceStorePostgresFixture.EVIDENCE_TABLE)
        return tenants.bind(tenant) { tx.inTransaction { block(ledger) } }
    }
}
