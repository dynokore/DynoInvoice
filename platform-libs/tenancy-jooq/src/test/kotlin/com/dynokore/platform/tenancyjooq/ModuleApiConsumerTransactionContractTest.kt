package com.dynokore.platform.tenancyjooq

import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.TableField
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.jooq.impl.SchemaImpl
import org.jooq.impl.TableImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The statement half of tenancy-kernel's consumer-driven contract test (coordinator ruling INV-Q-5208), moved here
 * because tenancy-kernel can build no DSLContext: a fake consumer with the three -api ports ([TenantContext],
 * [TransactionRunner] and the jOOQ context tenancy-jooq builds over `tenancy_kernel_test.probe`, declared TENANT) runs
 * against the real implementation. Before and after: `nested inTransaction joins one transaction (REQUIRED propagation)
 * and current() reflects the bound tenant` and `a failure anywhere in the nested transaction rolls back every write in
 * it
 * (ARC-05 atomicity)`, with the same compared values.
 */
@Tag("integration")
class ModuleApiConsumerTransactionContractTest {
    private object Probe : TableImpl<Record>(DSL.name("probe"), SchemaImpl(DSL.name("tenancy_kernel_test"), null)) {
        val ID: TableField<Record, UUID> = createField(DSL.name("id"), SQLDataType.UUID, this)
        val TENANT_ID: TableField<Record, UUID> = createField(DSL.name("tenant_id"), SQLDataType.UUID, this)
        val VALUE: TableField<Record, String> = createField(DSL.name("value"), SQLDataType.CLOB, this)
    }

    private val tables =
        TenancyTables.declared(mapOf(DSL.name("tenancy_kernel_test", "probe") to TableClass.TENANT))

    /** Written exactly as `submission-application`'s use cases are (TPL-04): the ports and a DSLContext. */
    private class FakeConsumerUseCase(
        private val tenants: TenantContext,
        private val tx: TransactionRunner,
        private val dsl: DSLContext,
    ) {
        fun recordTwoProbeRowsInOneTransaction(
            first: String,
            second: String,
        ) {
            tx.inTransaction {
                insert(first)
                tx.inTransaction { insert(second) } // nested: REQUIRED propagation
            }
        }

        fun recordThenFailMidway(
            first: String,
            second: String,
        ) {
            tx.inTransaction {
                insert(first)
                tx.inTransaction {
                    insert(second)
                    error("simulated failure after both writes, before commit")
                }
            }
        }

        fun currentTenant(): TenantId = tenants.current()

        fun values(): List<String> =
            tx.inTransaction {
                dsl
                    .select(Probe.VALUE)
                    .from(Probe)
                    .orderBy(Probe.VALUE)
                    .fetch(Probe.VALUE)
            }

        private fun insert(value: String) {
            dsl
                .insertInto(Probe)
                .columns(Probe.ID, Probe.TENANT_ID, Probe.VALUE)
                .values(UUID.randomUUID(), tenants.current().value, value)
                .execute()
        }
    }

    @Test
    fun `nested inTransaction joins one transaction (REQUIRED propagation) and current() reflects the bound tenant`() {
        val fixture = Databases.of(Images.PG18)
        val useCase = FakeConsumerUseCase(fixture.tenants, fixture.transactions, fixture.configuration(tables).dsl())
        val tenant = TenantId(UUID.randomUUID())

        val seenTenant = fixture.tenants.bind(tenant, "test-actor") { useCase.currentTenant() }
        assertEquals(tenant, seenTenant)

        fixture.tenants.bind(tenant, "test-actor") {
            useCase.recordTwoProbeRowsInOneTransaction("nested-first", "nested-second")
        }

        val rows = fixture.tenants.bind(tenant, "test-actor") { useCase.values() }
        assertTrue(
            rows.containsAll(listOf("nested-first", "nested-second")),
            "both nested writes must be visible: $rows",
        )
    }

    @Test
    fun `a failure anywhere in the nested transaction rolls back every write in it (ARC-05 atomicity)`() {
        val fixture = Databases.of(Images.PG18)
        val useCase = FakeConsumerUseCase(fixture.tenants, fixture.transactions, fixture.configuration(tables).dsl())
        val tenant = TenantId(UUID.randomUUID())

        assertThrows(IllegalStateException::class.java) {
            fixture.tenants.bind(tenant, "test-actor") {
                useCase.recordThenFailMidway("rolled-back-first", "rolled-back-second")
            }
        }

        val rows = fixture.tenants.bind(tenant, "test-actor") { useCase.values() }
        assertTrue(rows.none { it.startsWith("rolled-back") }, "neither write must survive the rollback: $rows")
    }
}
