package com.dynokore.platform.tenancyjooq

import com.dynokore.platform.tenancyjooq.Seed.CUSTOMER_A
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.FieldSource
import java.util.UUID

/**
 * Statements the layer accepts that do not depend on it to stay within the tenant (so [MutationTest] does not count
 * them): an INSERT that itself names the bound tenant, and a PLATFORM_SCOPED table, read under any binding and written
 * under the platform-tenant binding (INV-T-4133).
 */
class AcceptedStatementsTest {
    @ParameterizedTest
    @FieldSource("com.dynokore.platform.tenancyjooq.Images#ALL")
    fun `an INSERT that names the bound tenant is accepted`(image: String) {
        val fixture = Databases.of(image)
        Seed.reset(fixture)
        val order = UUID.randomUUID()
        fixture.asTenant(Tenants.A) {
            dsl(fixture)
                .insertInto(Orders, Orders.ID, Orders.TENANT_ID, Orders.CUSTOMER_ID, Orders.AMOUNT)
                .values(order, Tenants.A.value, CUSTOMER_A, 5)
                .execute()
        }
        assertEquals(Tenants.A.value, Seed.orderTenants(fixture)[order])
    }

    @ParameterizedTest
    @FieldSource("com.dynokore.platform.tenancyjooq.Images#ALL")
    fun `a tenant binding reads a PLATFORM_SCOPED table`(image: String) {
        val fixture = Databases.of(image)
        Seed.reset(fixture)
        val codes =
            fixture.asTenant(Tenants.B) {
                dsl(fixture).select(Countries.CODE).from(Countries).fetchSet(Countries.CODE)
            }
        assertEquals(setOf("DE", "FR"), codes)
    }

    @ParameterizedTest
    @FieldSource("com.dynokore.platform.tenancyjooq.Images#ALL")
    fun `the platform-tenant binding writes a PLATFORM_SCOPED table`(image: String) {
        val fixture = Databases.of(image)
        Seed.reset(fixture)
        fixture.asPlatform {
            dsl(fixture).insertInto(Countries, Countries.CODE, Countries.NAME).values("IT", "Italy").execute()
        }
        val codes =
            fixture.probe
                .fetchRows("select code from tenancy_kernel_test.countries")
                .map { it.getValue("code") }
                .toSet()
        assertEquals(setOf("DE", "FR", "IT"), codes)
    }
}
