package com.dynokore.platform.tenancyjooq

import com.dynokore.platform.tenancyjooq.Seed.CUSTOMER_A
import com.dynokore.platform.tenancyjooq.Seed.CUSTOMER_B
import com.dynokore.platform.tenancyjooq.Seed.ORDER_A1
import com.dynokore.platform.tenancyjooq.Seed.ORDER_A2
import com.dynokore.platform.tenancyjooq.Seed.ORDER_B1
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.FieldSource
import java.util.UUID

/**
 * Each statement shape returns or changes only the bound tenant's rows (INV-T-4133; INV-ADR-3968 item 4a), on
 * PostgreSQL 16, 17 and 18 with row-level security off on the test tables, so the jOOQ layer alone is proved. Every
 * test here sees or changes another tenant's rows when the layer is removed ([MutationTest]).
 */
class IsolationTest {
    private fun <T> asA(
        image: String,
        block: (org.jooq.DSLContext) -> T,
    ): T {
        val fixture = Databases.of(image)
        Seed.reset(fixture)
        return fixture.asTenant(Tenants.A) { block(dsl(fixture)) }
    }

    @ParameterizedTest
    @FieldSource("com.dynokore.platform.tenancyjooq.Images#ALL")
    fun `a select returns only the bound tenant's rows`(image: String) {
        val ids = asA(image) { it.select(Orders.ID).from(Orders).fetchSet(Orders.ID) }
        assertEquals(setOf(ORDER_A1, ORDER_A2), ids)
    }

    @ParameterizedTest
    @FieldSource("com.dynokore.platform.tenancyjooq.Images#ALL")
    fun `window functions see only the bound tenant's rows`(image: String) {
        val window = DSL.name("by_amount").`as`(DSL.orderBy(Orders.AMOUNT))
        val rows =
            asA(image) {
                it
                    .select(
                        Orders.ID,
                        DSL.rowNumber().over(DSL.orderBy(Orders.AMOUNT)),
                        DSL.lag(Orders.AMOUNT).over(DSL.orderBy(Orders.AMOUNT)),
                        DSL.sum(Orders.AMOUNT).over(DSL.partitionBy(Orders.TENANT_ID).orderBy(Orders.AMOUNT)),
                        DSL.rank().over(DSL.orderBy(Orders.AMOUNT)),
                        DSL.sum(Orders.AMOUNT).over(window),
                        DSL.rowNumber().over().partitionBy(Orders.TENANT_ID).orderBy(Orders.AMOUNT),
                        DSL.count().over(),
                    ).from(Orders)
                    .window(window)
                    .orderBy(Orders.AMOUNT)
                    .fetch()
                    .map { row -> row.intoList().map { value -> value?.toString() } }
            }
        assertEquals(
            listOf(
                listOf(ORDER_A1.toString(), "1", null, "10", "1", "10", "1", "2"),
                listOf(ORDER_A2.toString(), "2", "10", "21", "2", "21", "2", "2"),
            ),
            rows,
        )
    }

    @ParameterizedTest
    @FieldSource("com.dynokore.platform.tenancyjooq.Images#ALL")
    fun `an aliased table is limited through its alias`(image: String) {
        val o = Orders.`as`("o")
        val ids = asA(image) { it.select(o.field(Orders.ID)).from(o).fetchSet(o.field(Orders.ID)) }
        assertEquals(setOf(ORDER_A1, ORDER_A2), ids)
    }

    @ParameterizedTest
    @FieldSource("com.dynokore.platform.tenancyjooq.Images#ALL")
    fun `an inner join limits both sides`(image: String) {
        val rows =
            asA(image) {
                it
                    .select(Orders.ID, Customers.NAME)
                    .from(Orders)
                    .join(Customers)
                    .on(Customers.ID.eq(Orders.CUSTOMER_ID))
                    .fetch()
                    .map { row -> row.value1() to row.value2() }
                    .toSet()
            }
        assertEquals(setOf(ORDER_A1 to "Alice"), rows)
    }

    @ParameterizedTest
    @FieldSource("com.dynokore.platform.tenancyjooq.Images#ALL")
    fun `a left join limits its nullable side in ON, keeping the outer rows`(image: String) {
        val rows =
            asA(image) {
                it
                    .select(Orders.ID, Customers.NAME)
                    .from(Orders)
                    .leftJoin(Customers)
                    .on(Customers.ID.eq(Orders.CUSTOMER_ID))
                    .fetch()
                    .map { row -> row.value1() to row.value2() }
                    .toSet()
            }
        assertEquals(setOf(ORDER_A1 to "Alice", ORDER_A2 to null), rows)
    }

    @ParameterizedTest
    @FieldSource("com.dynokore.platform.tenancyjooq.Images#ALL")
    fun `a right join limits its nullable side in ON`(image: String) {
        val rows =
            asA(image) {
                it
                    .select(Orders.ID, Customers.NAME)
                    .from(Customers)
                    .rightJoin(Orders)
                    .on(Customers.ID.eq(Orders.CUSTOMER_ID))
                    .fetch()
                    .map { row -> row.value1() to row.value2() }
                    .toSet()
            }
        assertEquals(setOf(ORDER_A1 to "Alice", ORDER_A2 to null), rows)
    }

    @ParameterizedTest
    @FieldSource("com.dynokore.platform.tenancyjooq.Images#ALL")
    fun `a full join limits both sides`(image: String) {
        val rows =
            asA(image) {
                it
                    .select(Orders.ID, Customers.NAME)
                    .from(Orders)
                    .fullJoin(Customers)
                    .on(Customers.ID.eq(Orders.CUSTOMER_ID))
                    .fetch()
                    .map { row -> row.value1() to row.value2() }
                    .toSet()
            }
        assertEquals(setOf(ORDER_A1 to "Alice", ORDER_A2 to null), rows)
    }

    @ParameterizedTest
    @FieldSource("com.dynokore.platform.tenancyjooq.Images#ALL")
    fun `a subquery is limited`(image: String) {
        val ids =
            asA(image) {
                it
                    .select(Orders.ID)
                    .from(Orders)
                    .where(Orders.CUSTOMER_ID.`in`(DSL.select(Customers.ID).from(Customers)))
                    .fetchSet(Orders.ID)
            }
        assertEquals(setOf(ORDER_A1), ids)
    }

    @ParameterizedTest
    @FieldSource("com.dynokore.platform.tenancyjooq.Images#ALL")
    fun `a correlated EXISTS subquery is limited`(image: String) {
        val names =
            asA(image) {
                it
                    .select(Customers.NAME)
                    .from(Customers)
                    .whereExists(DSL.selectOne().from(Orders).where(Orders.CUSTOMER_ID.eq(Customers.ID)))
                    .fetchSet(Customers.NAME)
            }
        assertEquals(setOf("Alice"), names)
    }

    @ParameterizedTest
    @FieldSource("com.dynokore.platform.tenancyjooq.Images#ALL")
    fun `a CTE body is limited`(image: String) {
        val count =
            asA(image) {
                val cte = DSL.name("own_orders").`as`(DSL.select(Orders.ID).from(Orders))
                it.with(cte).selectCount().from(cte).fetchOne(0, Int::class.java)
            }
        assertEquals(2, count)
    }

    @ParameterizedTest
    @FieldSource("com.dynokore.platform.tenancyjooq.Images#ALL")
    fun `a derived table is limited`(image: String) {
        val count =
            asA(image) {
                it
                    .selectCount()
                    .from(DSL.select(Orders.ID).from(Orders).asTable("derived"))
                    .fetchOne(0, Int::class.java)
            }
        assertEquals(2, count)
    }

    @ParameterizedTest
    @FieldSource("com.dynokore.platform.tenancyjooq.Images#ALL")
    fun `each UNION branch is limited`(image: String) {
        val ids =
            asA(image) {
                it
                    .select(Orders.ID)
                    .from(Orders)
                    .union(DSL.select(Customers.ID).from(Customers))
                    .fetchSet(Orders.ID)
            }
        assertEquals(setOf(ORDER_A1, ORDER_A2, CUSTOMER_A), ids)
    }

    @ParameterizedTest
    @FieldSource("com.dynokore.platform.tenancyjooq.Images#ALL")
    fun `each INTERSECT branch is limited`(image: String) {
        val ids =
            asA(image) {
                it
                    .select(Orders.CUSTOMER_ID)
                    .from(Orders)
                    .intersect(DSL.select(Customers.ID).from(Customers))
                    .fetchSet(Orders.CUSTOMER_ID)
            }
        assertEquals(setOf(CUSTOMER_A), ids)
    }

    @ParameterizedTest
    @FieldSource("com.dynokore.platform.tenancyjooq.Images#ALL")
    fun `each EXCEPT branch is limited`(image: String) {
        val ids =
            asA(image) {
                it
                    .select(Orders.CUSTOMER_ID)
                    .from(Orders)
                    .except(DSL.select(Customers.ID).from(Customers))
                    .fetchSet(Orders.CUSTOMER_ID)
            }
        assertEquals(setOf(CUSTOMER_B), ids)
    }

    @ParameterizedTest
    @FieldSource("com.dynokore.platform.tenancyjooq.Images#ALL")
    fun `a REFERENCE read returns the tenant's and the platform rows`(image: String) {
        val codes = asA(image) { it.select(Currencies.CODE).from(Currencies).fetchSet(Currencies.CODE) }
        assertEquals(setOf("EUR", "XAA"), codes)
    }

    @ParameterizedTest
    @FieldSource("com.dynokore.platform.tenancyjooq.Images#ALL")
    fun `an UPDATE changes only the bound tenant's rows`(image: String) {
        val fixture = Databases.of(image)
        val updated = asA(image) { it.update(Orders).set(Orders.AMOUNT, 99).execute() }
        assertEquals(2, updated)
        assertEquals(mapOf(ORDER_A1 to 99, ORDER_A2 to 99, ORDER_B1 to 20), Seed.orderAmounts(fixture))
    }

    @ParameterizedTest
    @FieldSource("com.dynokore.platform.tenancyjooq.Images#ALL")
    fun `an UPDATE with FROM limits the target and the joined table`(image: String) {
        val fixture = Databases.of(image)
        val updated =
            asA(image) {
                it
                    .update(Orders)
                    .set(Orders.AMOUNT, 77)
                    .from(Customers)
                    .where(Customers.ID.eq(Orders.CUSTOMER_ID))
                    .execute()
            }
        assertEquals(1, updated)
        assertEquals(mapOf(ORDER_A1 to 77, ORDER_A2 to 11, ORDER_B1 to 20), Seed.orderAmounts(fixture))
    }

    @ParameterizedTest
    @FieldSource("com.dynokore.platform.tenancyjooq.Images#ALL")
    fun `a DELETE removes only the bound tenant's rows`(image: String) {
        val fixture = Databases.of(image)
        val deleted = asA(image) { it.deleteFrom(Orders).execute() }
        assertEquals(2, deleted)
        assertEquals(setOf(ORDER_B1), Seed.orderAmounts(fixture).keys)
    }

    @ParameterizedTest
    @FieldSource("com.dynokore.platform.tenancyjooq.Images#ALL")
    fun `a DELETE with USING limits the target and the joined table`(image: String) {
        val fixture = Databases.of(image)
        val deleted =
            asA(image) {
                it
                    .deleteFrom(Orders)
                    .using(Customers)
                    .where(Customers.ID.eq(Orders.CUSTOMER_ID))
                    .execute()
            }
        assertEquals(1, deleted)
        assertEquals(setOf(ORDER_A2, ORDER_B1), Seed.orderAmounts(fixture).keys)
    }

    @ParameterizedTest
    @FieldSource("com.dynokore.platform.tenancyjooq.Images#ALL")
    fun `an INSERT sets the tenant to the bound tenant`(image: String) {
        val fixture = Databases.of(image)
        val order = UUID.randomUUID()
        asA(image) {
            it
                .insertInto(Orders, Orders.ID, Orders.CUSTOMER_ID, Orders.AMOUNT)
                .values(order, CUSTOMER_A, 5)
                .execute()
        }
        assertEquals(Tenants.A.value, Seed.orderTenants(fixture)[order])
    }

    @ParameterizedTest
    @FieldSource("com.dynokore.platform.tenancyjooq.Images#ALL")
    fun `an INSERT from a SELECT copies only the bound tenant's rows, as the bound tenant`(image: String) {
        val fixture = Databases.of(image)
        val inserted =
            asA(image) {
                it
                    .insertInto(Orders, Orders.ID, Orders.CUSTOMER_ID, Orders.AMOUNT)
                    .select(DSL.select(DSL.uuid(), Orders.CUSTOMER_ID, Orders.AMOUNT.plus(1000)).from(Orders))
                    .execute()
            }
        assertEquals(2, inserted)
        val tenants = Seed.orderTenants(fixture).values.groupingBy { it }.eachCount()
        assertEquals(mapOf(Tenants.A.value to 4, Tenants.B.value to 1), tenants)
    }

    @ParameterizedTest
    @FieldSource("com.dynokore.platform.tenancyjooq.Images#ALL")
    fun `ON CONFLICT DO UPDATE never updates another tenant's row`(image: String) {
        val fixture = Databases.of(image)
        val changed =
            asA(image) {
                it
                    .insertInto(Orders, Orders.ID, Orders.CUSTOMER_ID, Orders.AMOUNT)
                    .values(ORDER_B1, CUSTOMER_A, 1)
                    .values(ORDER_A1, CUSTOMER_A, 1)
                    .onConflict(Orders.ID)
                    .doUpdate()
                    .set(Orders.AMOUNT, 1)
                    .execute()
            }
        assertEquals(1, changed)
        assertEquals(mapOf(ORDER_A1 to 1, ORDER_A2 to 11, ORDER_B1 to 20), Seed.orderAmounts(fixture))
        assertEquals(Tenants.B.value, Seed.orderTenants(fixture)[ORDER_B1])
    }

    @ParameterizedTest
    @FieldSource("com.dynokore.platform.tenancyjooq.Images#ALL")
    fun `a MERGE matches and updates only the bound tenant's rows`(image: String) {
        val fixture = Databases.of(image)
        asA(image) {
            it
                .mergeInto(Orders)
                .using(Customers)
                .on(Orders.CUSTOMER_ID.eq(Customers.ID))
                .whenMatchedThenUpdate()
                .set(Orders.AMOUNT, 500)
                .execute()
        }
        assertEquals(mapOf(ORDER_A1 to 500, ORDER_A2 to 11, ORDER_B1 to 20), Seed.orderAmounts(fixture))
    }

    @ParameterizedTest
    @FieldSource("com.dynokore.platform.tenancyjooq.Images#ALL")
    fun `a MERGE inserts as the bound tenant`(image: String) {
        val fixture = Databases.of(image)
        val order = UUID.randomUUID()
        asA(image) {
            it
                .mergeInto(Orders)
                .using(DSL.selectOne())
                .on(Orders.ID.eq(order))
                .whenNotMatchedThenInsert(Orders.ID, Orders.CUSTOMER_ID, Orders.AMOUNT)
                .values(order, CUSTOMER_A, 3)
                .execute()
        }
        assertEquals(Tenants.A.value, Seed.orderTenants(fixture)[order])
    }

    @ParameterizedTest
    @FieldSource("com.dynokore.platform.tenancyjooq.Images#ALL")
    fun `a REFERENCE write changes only the tenant's own rows, never a platform row`(image: String) {
        val fixture = Databases.of(image)
        asA(image) {
            it.insertInto(Currencies, Currencies.CODE, Currencies.NAME).values("XA2", "A coin 2").execute()
            it.update(Currencies).set(Currencies.NAME, "renamed").execute()
        }
        val platform = TenantId.PLATFORM.value
        assertEquals(
            mapOf(
                (platform to "EUR") to "Euro",
                (Tenants.A.value to "XAA") to "renamed",
                (Tenants.A.value to "XA2") to "renamed",
                (Tenants.B.value to "XBB") to "B coin",
            ),
            Seed.currencies(fixture),
        )
    }
}
