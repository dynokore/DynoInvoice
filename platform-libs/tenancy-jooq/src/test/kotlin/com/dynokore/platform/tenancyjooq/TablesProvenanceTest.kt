package com.dynokore.platform.tenancyjooq

import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.Configuration
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.impl.DefaultConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [TenancyJooq.problem] refuses a configuration whose TenancyTables did not come from [TableClasses.verify]
 * (owner answer INV-Q-4821 B), while [TenancyJooq.configure], [TenancyJooq.install] and the library
 * [TenancyJooq.dslContext] keep accepting what they are given.
 */
class TablesProvenanceTest {
    private val fixture = Databases.of(Images.PG18)
    private val orders = mapOf(DSL.name("tenancy_kernel_test", "orders") to TableClass.TENANT)

    private fun declaredConfiguration(): Configuration =
        TenancyJooq.configure(
            DefaultConfiguration().set(SQLDialect.POSTGRES),
            TenancyTables.declared(orders),
            fixture.tenants,
            fixture.transactions,
        )

    private fun verifiedConfiguration(): Configuration =
        fixture.configuration(TableClasses.of(emptyList(), emptyList()).verify(TestSchema))

    private fun mixed(
        visit: Configuration,
        execute: Configuration,
    ): Configuration =
        DefaultConfiguration()
            .set(SQLDialect.POSTGRES)
            .set(visit.connectionProvider())
            .set(*visit.visitListenerProviders())
            .set(*execute.executeListenerProviders())

    @Test
    fun `a configuration of a verified TenancyTables has no problem`() {
        assertNull(TenancyJooq.problem(fixture.configuration(TEST_TABLES)))
    }

    @Test
    fun `a configuration of a declared TenancyTables is refused, naming verify`() {
        val text = checkNotNull(TenancyJooq.problem(declaredConfiguration()))
        assertTrue("TableClasses.verify" in text && "INV-Q-4821" in text, text)
    }

    @Test
    fun `the library dslContext has no problem`() {
        val transactions: TransactionRunner = fixture.transactions
        assertNull(TenancyJooq.problem(TenancyJooq.dslContext(orders, fixture.tenants, transactions).configuration()))
    }

    @Test
    fun `listeners of a verified and of a declared instance are refused`() {
        assertNotNull(TenancyJooq.problem(mixed(fixture.configuration(TEST_TABLES), declaredConfiguration())))
    }

    @Test
    fun `listeners of two different verified instances are refused`() {
        val text = TenancyJooq.problem(mixed(verifiedConfiguration(), verifiedConfiguration()))
        assertNotNull(text)
        assertEquals(true, text?.contains("TableClasses.verify"))
    }

    @Test
    fun `install accepts a declared instance and the guard refuses it`() {
        val boot = DefaultConfiguration()
        boot.set(SQLDialect.POSTGRES)
        TenancyJooq.install(boot, TenancyTables.declared(orders), fixture.tenants, fixture.transactions)
        assertNotNull(TenancyJooq.problem(boot))
    }
}
