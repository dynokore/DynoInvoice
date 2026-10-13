package com.dynokore.platform.tenancyjooq

import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DefaultConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * The overhead benchmark of INV-T-4133 (timed loop, 100,000 renders): the same join query rendered by a plain jOOQ
 * DSLContext and by tenancy-jooq's configured one (VisitListener), and executed against jOOQ's mock driver (so only
 * rendering and the listeners are timed) without and with both tenancy listeners. The numbers are printed for the
 * merge request; the test asserts only that the layer changed the SQL.
 */
class OverheadBenchmarkTest {
    private val renders = 100_000

    @Test
    fun `100,000 renders with and without the tenancy layer`() {
        val fixture = Databases.of(Images.PG18)
        val plain = DefaultConfiguration().set(SQLDialect.POSTGRES)
        val tenancy = fixture.configuration(TEST_TABLES)
        val query = { dsl: DSLContext ->
            dsl
                .select(Orders.ID, Customers.NAME)
                .from(Orders)
                .leftJoin(Customers)
                .on(Customers.ID.eq(Orders.CUSTOMER_ID))
                .where(Orders.AMOUNT.gt(10))
        }
        val result = plain.dsl().newResult(Orders.ID, Customers.NAME)
        val mockPlain = MockExecution.configuration(plain, result).dsl()
        val mockTenancy = MockExecution.configuration(tenancy, result).dsl()
        val lines =
            fixture.tenants.bind(Tenants.A, "benchmark") {
                assertEquals(
                    false,
                    plain.dsl().render(query(plain.dsl())) ==
                        tenancy.dsl().render(query(tenancy.dsl())),
                )
                listOf(
                    time("render, plain DSLContext") { plain.dsl().render(query(plain.dsl())) },
                    time("render, tenancy-jooq VisitListener") { tenancy.dsl().render(query(tenancy.dsl())) },
                    time("execute on the mock driver, plain") { query(mockPlain).fetch() },
                    time("execute on the mock driver, VisitListener + ExecuteListener check") {
                        query(mockTenancy).fetch()
                    },
                )
            }
        lines.forEach { println("tenancy-jooq overhead benchmark: $it") }
    }

    private fun time(
        label: String,
        block: () -> Any?,
    ): String {
        repeat(renders / 5) { block() } // warm-up
        val start = System.nanoTime()
        repeat(renders) { block() }
        val nanos = System.nanoTime() - start
        return "%s: %,d in %,d ms (%.2f us each)"
            .format(Locale.ROOT, label, renders, nanos / 1_000_000, nanos / 1_000.0 / renders)
    }
}
