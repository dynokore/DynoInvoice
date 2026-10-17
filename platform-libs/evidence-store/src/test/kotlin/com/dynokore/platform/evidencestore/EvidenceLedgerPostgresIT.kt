package com.dynokore.platform.evidencestore

import com.dynokore.platform.tenancyjooq.TableClass
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TestPoolFixture
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import org.jooq.DSLContext
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The done-when's own acceptance test: hash-chained append-only evidence,
 * refused in-place update by both DB grants and a trigger, indexed retrieval
 * scoped to entity and period, and tenant isolation — proved against real
 * PostgreSQL 17 and 18 (STK-39), not a fake. The database is reached only through
 * `TestPoolFixture`, a `TransactionRunner` and tenancy-jooq's `DSLContext` (owner ruling INV-Q-4508 (A)); the
 * tables are created by the fixture's init script.
 */
@Tag("integration")
class EvidenceLedgerPostgresIT {
    private class Db(
        fixture: EvidenceStorePostgresFixture,
    ) : AutoCloseable {
        val tenants = TenantContextImpl()
        private val appPool =
            TestPoolFixture(
                fixture.jdbcUrl(),
                EvidenceStorePostgresFixture.APP_USER,
                EvidenceStorePostgresFixture.APP_PASSWORD,
                poolName = "evidence-app",
            )
        private val ownerPool =
            TestPoolFixture(
                fixture.jdbcUrl(),
                EvidenceStorePostgresFixture.OWNER_USER,
                EvidenceStorePostgresFixture.OWNER_PASSWORD,
                poolName = "evidence-owner",
            )

        /** The runtime application role: `NOSUPERUSER`, `NOBYPASSRLS`, owns no table (S2-05). */
        val tx: TransactionRunnerImpl = appPool.runner(tenants)

        /** The migration/owner role: it still holds UPDATE/DELETE on its own tables. */
        val ownerTx: TransactionRunnerImpl = ownerPool.runner(tenants)
        val appDsl: DSLContext = dsl(tx)
        val ownerDsl: DSLContext = dsl(ownerTx)
        val ledger = JdbcEvidenceLedger(appDsl, SCHEMA, EVIDENCE_TABLE)
        val registry = JdbcLegalHoldRegistry(appDsl, SCHEMA, LEGAL_HOLD_TABLE)

        private fun dsl(runner: TransactionRunnerImpl): DSLContext =
            TenancyJooq.dslContext(
                mapOf(
                    DSL.name(SCHEMA, EVIDENCE_TABLE) to TableClass.TENANT,
                    DSL.name(SCHEMA, LEGAL_HOLD_TABLE) to TableClass.TENANT,
                ),
                tenants,
                runner,
            )

        override fun close() {
            appPool.close()
            ownerPool.close()
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `append-only, chained, tenant-isolated evidence with a working legal hold`(image: String) {
        EvidenceStorePostgresFixture(image).use { fixture ->
            Db(fixture).use { db ->
                assertAppendChainsAndIsRetrievable(db)
                assertAppRoleCannotUpdateOrDelete(db)
                assertTriggerRefusesMutationEvenForTheOwnerRole(db)
                assertRlsIsolatesTenants(db)
                assertTamperedRowIsDetectedByVerifyChain(db)
                assertLegalHoldPlaceAndRelease(db)
            }
        }
    }

    private fun assertAppendChainsAndIsRetrievable(db: Db) {
        val tenant = TenantId(UUID.randomUUID())
        val entity = EntityRef("entity-1")
        val period = PeriodRef("2026-Q1")

        val recorded =
            runInTransaction(db, tenant) { ledger ->
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

        val found = runInTransaction(db, tenant) { ledger -> ledger.findByEntityAndPeriod(tenant, entity, period) }
        assertEquals(recorded, found)

        val exported = runInTransaction(db, tenant) { ledger -> ledger.bulkExport(tenant, entity, period).toList() }
        assertEquals(recorded, exported)

        val verified = runInTransaction(db, tenant) { ledger -> ledger.verifyChain(tenant, entity, period) }
        assertEquals(ChainVerificationResult.Valid(3), verified)
    }

    /** CS-22: "DB grants: no UPDATE, DELETE or TRUNCATE on those tables for the service role". */
    private fun assertAppRoleCannotUpdateOrDelete(db: Db) {
        val tenant = TenantId(UUID.randomUUID())
        val table = DSL.table(DSL.name(SCHEMA, EVIDENCE_TABLE))
        assertThrows(DataAccessException::class.java) {
            db.tenants.bind(tenant, "test-actor") {
                db.tx.inTransaction {
                    db.appDsl
                        .update(table)
                        .set(CONTENT, CONTENT)
                        .execute()
                }
            }
        }
        assertThrows(DataAccessException::class.java) {
            db.tenants.bind(tenant, "test-actor") {
                db.tx.inTransaction { db.appDsl.deleteFrom(table).execute() }
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
    private fun assertTriggerRefusesMutationEvenForTheOwnerRole(db: Db) {
        val tenant = TenantId(UUID.randomUUID())
        val table = DSL.table(DSL.name(SCHEMA, EVIDENCE_TABLE))
        ownerInsert(db, tenant, "trigger-entity", "trigger-period", "seed".toByteArray(), "1".repeat(64))
        val update =
            assertThrows(DataAccessException::class.java) {
                db.tenants.bind(tenant, "test-owner") {
                    db.ownerTx.inTransaction {
                        db.ownerDsl
                            .update(table)
                            .set(CONTENT, CONTENT)
                            .execute()
                    }
                }
            }
        assertTrue(
            update.message?.contains("append-only") == true,
            "expected the CS-22 trigger message, was: ${update.message}",
        )
        val delete =
            assertThrows(DataAccessException::class.java) {
                db.tenants.bind(tenant, "test-owner") {
                    db.ownerTx.inTransaction { db.ownerDsl.deleteFrom(table).execute() }
                }
            }
        assertTrue(
            delete.message?.contains("append-only") == true,
            "expected the CS-22 trigger message, was: ${delete.message}",
        )
    }

    private fun assertRlsIsolatesTenants(db: Db) {
        val tenantA = TenantId(UUID.randomUUID())
        val tenantB = TenantId(UUID.randomUUID())
        val entity = EntityRef("shared-entity")
        val period = PeriodRef("2026-Q2")

        runInTransaction(db, tenantB) { ledger ->
            ledger.append(tenantB, entity, period, EvidenceType.AUDIT_LOG, "b-only".toByteArray())
        }

        val visibleToA =
            runInTransaction(db, tenantA) { ledger -> ledger.findByEntityAndPeriod(tenantA, entity, period) }
        assertTrue(visibleToA.isEmpty(), "tenant A must never see tenant B's evidence, saw $visibleToA")
    }

    /**
     * With the trigger structurally in the way of an UPDATE, tampering is
     * modelled the way it would really happen — a bad row landing in the
     * table some other way (a restore, a bug in a future writer) — rather
     * than by defeating the guarantee the previous two assertions just
     * proved. The owner role writes it (route R4), under the tenant's binding (WITH CHECK, CS-19).
     */
    private fun assertTamperedRowIsDetectedByVerifyChain(db: Db) {
        val tenant = TenantId(UUID.randomUUID())
        val entity = EntityRef("tamper-entity")
        val period = PeriodRef("2026-Q3")

        runInTransaction(db, tenant) { ledger ->
            ledger.append(tenant, entity, period, EvidenceType.PAYLOAD, "genuine".toByteArray())
        }
        // an arbitrary hash, not the real chain link
        ownerInsert(db, tenant, entity.value, period.value, "tampered".toByteArray(), "0".repeat(64), seq = 1)

        val result = runInTransaction(db, tenant) { ledger -> ledger.verifyChain(tenant, entity, period) }
        assertTrue(result is ChainVerificationResult.Broken, "expected Broken, was $result")
        assertEquals(1L, (result as ChainVerificationResult.Broken).atSequence)
    }

    private fun assertLegalHoldPlaceAndRelease(db: Db) {
        val tenant = TenantId(UUID.randomUUID())
        val entity = EntityRef("hold-entity")
        val period = PeriodRef("2026-Q4")

        db.tenants.bind(tenant, "test-actor") {
            db.tx.inTransaction { assertFalse(db.registry.isHeld(tenant, entity, period)) }
            db.tx.inTransaction { db.registry.place(tenant, entity, period, LegalHold("under audit", Instant.now())) }
            db.tx.inTransaction { assertTrue(db.registry.isHeld(tenant, entity, period)) }
            db.tx.inTransaction { db.registry.release(tenant, entity, period) }
            db.tx.inTransaction { assertFalse(db.registry.isHeld(tenant, entity, period)) }
        }
    }

    /** One evidence row written by the schema owner under [tenant]'s binding (WITH CHECK applies, CS-19). */
    private fun ownerInsert(
        db: Db,
        tenant: TenantId,
        entity: String,
        period: String,
        content: ByteArray,
        hash: String,
        seq: Long = 0,
    ) {
        db.tenants.bind(tenant, "test-owner") {
            db.ownerTx.inTransaction {
                db.ownerDsl
                    .insertInto(DSL.table(DSL.name(SCHEMA, EVIDENCE_TABLE)))
                    .columns(ID, TENANT, ENTITY, PERIOD, SEQ, TYPE, CONTENT, HASH, RECORDED)
                    .values(
                        UUID.randomUUID(),
                        tenant.value,
                        entity,
                        period,
                        seq,
                        "PAYLOAD",
                        content,
                        hash,
                        Instant.now().atOffset(ZoneOffset.UTC),
                    ).execute()
            }
        }
    }

    private fun <T> runInTransaction(
        db: Db,
        tenant: TenantId,
        block: (EvidenceLedger) -> T,
    ): T = db.tenants.bind(tenant, "test-actor") { db.tx.inTransaction { block(db.ledger) } }

    private companion object {
        const val SCHEMA = EvidenceStorePostgresFixture.SCHEMA
        const val EVIDENCE_TABLE = EvidenceStorePostgresFixture.EVIDENCE_TABLE
        const val LEGAL_HOLD_TABLE = EvidenceStorePostgresFixture.LEGAL_HOLD_TABLE
        val ID = DSL.field(DSL.name("id"), SQLDataType.UUID)
        val TENANT = DSL.field(DSL.name("tenant_id"), SQLDataType.UUID)
        val ENTITY = DSL.field(DSL.name("entity_ref"), SQLDataType.CLOB)
        val PERIOD = DSL.field(DSL.name("period_ref"), SQLDataType.CLOB)
        val SEQ = DSL.field(DSL.name("seq"), SQLDataType.BIGINT)
        val TYPE = DSL.field(DSL.name("evidence_type"), SQLDataType.CLOB)
        val CONTENT = DSL.field(DSL.name("content"), SQLDataType.BLOB)
        val HASH = DSL.field(DSL.name("content_hash"), SQLDataType.CLOB)
        val RECORDED = DSL.field(DSL.name("recorded_at"), SQLDataType.TIMESTAMPWITHTIMEZONE)
    }
}
