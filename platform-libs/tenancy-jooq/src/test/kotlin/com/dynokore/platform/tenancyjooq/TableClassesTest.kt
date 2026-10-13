package com.dynokore.platform.tenancyjooq

import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** `TableClasses.verify(schema)` fails start-up on every disagreement with the generated schema (INV-T-4133). */
class TableClassesTest {
    private val countries = "tenancy_kernel_test.countries"
    private val currencies = "tenancy_kernel_test.currencies"

    @Test
    fun `every table is classified, TENANT by default`() {
        assertEquals(TableClass.TENANT, TEST_TABLES.classOf(Orders.qualifiedName))
        assertEquals(TableClass.TENANT, TEST_TABLES.classOf(Customers.qualifiedName))
        assertEquals(TableClass.REFERENCE, TEST_TABLES.classOf(Currencies.qualifiedName))
        assertEquals(TableClass.PLATFORM_SCOPED, TEST_TABLES.classOf(Countries.qualifiedName))
        assertNull(TEST_TABLES.classOf(Unknown.qualifiedName))
    }

    @Test
    fun `an undeclared table without a tenant column fails`() {
        val failure =
            assertThrows<IllegalStateException> { TableClasses.of(reference = listOf(currencies)).verify(TestSchema) }
        assertTrue(
            "tenancy_kernel_test.countries has no tenant_id column" in failure.message.orEmpty(),
            failure.message,
        )
    }

    @Test
    fun `a PLATFORM_SCOPED table with a tenant column fails`() {
        val failure =
            assertThrows<IllegalStateException> {
                TableClasses
                    .of(
                        platformScoped = listOf(countries, "tenancy_kernel_test.orders"),
                        reference = listOf(currencies),
                    ).verify(TestSchema)
            }
        assertTrue(
            "tenancy_kernel_test.orders is declared PLATFORM_SCOPED but has a tenant_id column" in
                failure.message.orEmpty(),
            failure.message,
        )
    }

    @Test
    fun `a declared table that is missing fails`() {
        val failure =
            assertThrows<IllegalStateException> {
                TableClasses
                    .of(platformScoped = listOf(countries, "tenancy_kernel_test.gone"), reference = listOf(currencies))
                    .verify(TestSchema)
            }
        assertTrue("tenancy_kernel_test.gone is declared but missing" in failure.message.orEmpty())
    }

    @Test
    fun `a REFERENCE table without a tenant column fails`() {
        assertThrows<IllegalStateException> {
            TableClasses.of(reference = listOf(countries, currencies)).verify(TestSchema)
        }
    }

    @Test
    fun `a declaration is by qualified name, once`() {
        assertThrows<IllegalArgumentException> { TableClasses.of(platformScoped = listOf("countries")) }
        assertThrows<IllegalArgumentException> {
            TableClasses.of(platformScoped = listOf(countries), reference = listOf(countries))
        }
        assertThrows<IllegalArgumentException> {
            TenancyTables.declared(mapOf(DSL.name("orders") to TableClass.TENANT))
        }
    }

    @Test
    fun `a platform-libs module declares its DSL name tables`() {
        val tables = TenancyTables.declared(mapOf(DSL.name("outbox", "message") to TableClass.TENANT))
        assertEquals(TableClass.TENANT, tables.classOf(DSL.name("outbox", "message")))
        assertEquals(listOf("outbox.message"), tables.names)
    }
}
