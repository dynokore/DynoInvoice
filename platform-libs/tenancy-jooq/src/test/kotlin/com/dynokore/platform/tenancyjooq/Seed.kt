package com.dynokore.platform.tenancyjooq

import com.dynokore.platform.tenancykernel.TenantId
import java.util.UUID

/**
 * The two-tenant data of the isolation tests, written and read through [TenancyProbe] with no tenant bound (row-level
 * security is off on these tables, so the probe sees every tenant's rows).
 */
internal object Seed {
    val CUSTOMER_A: UUID = id("customer-a")
    val CUSTOMER_B: UUID = id("customer-b")

    /** Tenant A's order of its own customer. */
    val ORDER_A1: UUID = id("order-a1")

    /** Tenant A's order whose customer id is tenant B's customer. */
    val ORDER_A2: UUID = id("order-a2")
    val ORDER_B1: UUID = id("order-b1")

    fun id(name: String): UUID = UUID.nameUUIDFromBytes(name.toByteArray())

    fun reset(fixture: TenancyJooqFixture) {
        fixture.probe.unbound {
            listOf("orders", "customers", "currencies", "countries").forEach {
                fetchValue(
                    "with deleted as (delete from tenancy_kernel_test.$it returning 1) " +
                        "select count(*) from deleted",
                )
            }
            customer(CUSTOMER_A, Tenants.A, "Alice")
            customer(CUSTOMER_B, Tenants.B, "Bob")
            order(ORDER_A1, Tenants.A, CUSTOMER_A, 10)
            order(ORDER_A2, Tenants.A, CUSTOMER_B, 11)
            order(ORDER_B1, Tenants.B, CUSTOMER_B, 20)
            currency("EUR", TenantId.PLATFORM, "Euro")
            currency("XAA", Tenants.A, "A coin")
            currency("XBB", Tenants.B, "B coin")
            fetchRows(
                "insert into tenancy_kernel_test.countries (code, name) " +
                    "values ('DE', 'Germany'), ('FR', 'France') returning code",
            )
        }
    }

    /** The tenant of each order, by id, as the database holds them. */
    fun orderTenants(fixture: TenancyJooqFixture): Map<UUID, UUID> {
        val rows = fixture.probe.fetchRows("select id, tenant_id from tenancy_kernel_test.orders")
        return rows.associate {
            it.getValue("id") as UUID to it.getValue("tenant_id") as UUID
        }
    }

    /** The amount of each order, by id. */
    fun orderAmounts(fixture: TenancyJooqFixture): Map<UUID, Int> {
        val rows = fixture.probe.fetchRows("select id, amount from tenancy_kernel_test.orders")
        return rows.associate {
            it.getValue("id") as UUID to it.getValue("amount") as Int
        }
    }

    /** Each currency's name, by tenant and code. */
    fun currencies(fixture: TenancyJooqFixture): Map<Pair<UUID, String>, String> {
        val rows = fixture.probe.fetchRows("select tenant_id, code, name from tenancy_kernel_test.currencies")
        return rows.associate {
            (it.getValue("tenant_id") as UUID to it.getValue("code") as String) to it.getValue("name") as String
        }
    }

    private fun TenancyProbe.customer(
        id: UUID,
        tenant: TenantId,
        name: String,
    ) = fetchValue(
        "insert into tenancy_kernel_test.customers (id, tenant_id, name) values (?, ?, ?) returning id",
        id,
        tenant.value,
        name,
    )

    private fun TenancyProbe.order(
        id: UUID,
        tenant: TenantId,
        customer: UUID,
        amount: Int,
    ) = fetchValue(
        "insert into tenancy_kernel_test.orders (id, tenant_id, customer_id, amount) values (?, ?, ?, ?) returning id",
        id,
        tenant.value,
        customer,
        amount,
    )

    private fun TenancyProbe.currency(
        code: String,
        tenant: TenantId,
        name: String,
    ) = fetchValue(
        "insert into tenancy_kernel_test.currencies (code, tenant_id, name) values (?, ?, ?) returning code",
        code,
        tenant.value,
        name,
    )
}
