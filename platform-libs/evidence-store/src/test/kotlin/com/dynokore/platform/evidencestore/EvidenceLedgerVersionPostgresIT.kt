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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

/** INV-REQ-942: the optional DECISION_TRACE version, against real PostgreSQL (same tag as EvidenceLedgerPostgresIT). */
@Tag("integration")
class EvidenceLedgerVersionPostgresIT {
    private class Ctx(
        val tenants: TenantContextImpl,
        val tx: TransactionRunnerImpl,
        val dsl: DSLContext,
    ) {
        val l1 = JdbcEvidenceLedger(dsl, SCHEMA, VERSIONED, versionColumn = true)
        val l2 = JdbcEvidenceLedger(dsl, SCHEMA, PLAIN)
        val tenant = TenantId(UUID.randomUUID())
        val entity = EntityRef("e")
        val period = PeriodRef("2026-Q1")

        fun <T> run(block: () -> T): T = tenants.bind(tenant, "test-actor") { tx.inTransaction { block() } }

        fun append(
            ledger: EvidenceLedger,
            type: EvidenceType,
            content: String,
            version: Int?,
            on: EntityRef = entity,
        ) = run { ledger.append(tenant, on, period, type, content.toByteArray(), EvidenceIndex(version = version)) }

        fun find(ledger: EvidenceLedger) = run { ledger.findByEntityAndPeriod(tenant, entity, period) }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `INV-AC-20746 a version is stored on a ledger told of the column and refused everywhere else`(image: String) {
        val extra =
            "create table $SCHEMA.$VERSIONED (${JdbcEvidenceLedger.COLUMNS}, " +
                "version integer check (version is null or version >= 1));\n" +
                EvidenceLedgerSchema.trailerDdl(SCHEMA, VERSIONED, EvidenceStorePostgresFixture.APP_USER) + "\n"
        EvidenceStorePostgresFixture(image, extra).use { fixture ->
            val tenants = TenantContextImpl()
            val pool =
                TestPoolFixture(
                    fixture.jdbcUrl(),
                    EvidenceStorePostgresFixture.APP_USER,
                    EvidenceStorePostgresFixture.APP_PASSWORD,
                    poolName = "evidence-version-app",
                )
            pool.use {
                val tx = pool.runner(tenants, keys = fixture.keys)
                val tables =
                    mapOf(
                        DSL.name(SCHEMA, PLAIN) to TableClass.TENANT,
                        DSL.name(SCHEMA, VERSIONED) to TableClass.TENANT,
                    )
                val ctx = Ctx(tenants, tx, TenancyJooq.dslContext(tables, tenants, tx))
                assertL1StoresAndRefuses(ctx)
                assertL2RefusesVersion(ctx)
                assertHashIgnoresVersion(ctx)
                assertGrantsStillRefuseMutation(ctx)
            }
        }
    }

    private fun assertL1StoresAndRefuses(c: Ctx) {
        val first = c.append(c.l1, EvidenceType.DECISION_TRACE, "a", 3)
        val second = c.append(c.l1, EvidenceType.DECISION_TRACE, "b", null)
        assertThrows(IllegalArgumentException::class.java) { c.append(c.l1, EvidenceType.PAYLOAD, "c", 3) }
        assertThrows(IllegalArgumentException::class.java) { c.append(c.l1, EvidenceType.DECISION_TRACE, "d", 0) }
        val stored = c.find(c.l1)
        assertEquals(listOf(first, second), stored)
        assertEquals(3, stored[0].index.version)
        assertNull(stored[1].index.version)
        assertEquals(ChainVerificationResult.Valid(2), c.run { c.l1.verifyChain(c.tenant, c.entity, c.period) })
    }

    private fun assertL2RefusesVersion(c: Ctx) {
        val plain = c.append(c.l2, EvidenceType.DECISION_TRACE, "a", null)
        assertNull(plain.index.version)
        assertThrows(IllegalArgumentException::class.java) { c.append(c.l2, EvidenceType.DECISION_TRACE, "z", 3) }
        assertEquals(listOf(plain), c.find(c.l2))
    }

    private fun assertHashIgnoresVersion(c: Ctx) {
        val v7 = c.append(c.l1, EvidenceType.DECISION_TRACE, "same", 7, on = EntityRef("e2"))
        val v8 = c.append(c.l1, EvidenceType.DECISION_TRACE, "same", 8, on = EntityRef("e3"))
        assertEquals(v7.contentHash, v8.contentHash)
    }

    private fun assertGrantsStillRefuseMutation(c: Ctx) {
        val table = DSL.table(DSL.name(SCHEMA, VERSIONED))
        assertThrows(DataAccessException::class.java) {
            c.run {
                c.dsl
                    .update(table)
                    .set(DSL.field(DSL.name("version"), Int::class.java), 9)
                    .execute()
            }
        }
        assertThrows(DataAccessException::class.java) { c.run { c.dsl.deleteFrom(table).execute() } }
    }

    private companion object {
        const val SCHEMA = EvidenceStorePostgresFixture.SCHEMA
        const val PLAIN = EvidenceStorePostgresFixture.EVIDENCE_TABLE
        const val VERSIONED = "evidence_record_v"
    }
}
