package com.dynokore.platform.tenancykernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.Connection
import java.sql.PreparedStatement
import java.util.Locale
import java.util.UUID

/**
 * INV-T-4156 performance gate (INV-REQ-854 b7, owner ruling INV-Q-4121 as ruled by INV-Q-6961, option A of the owner
 * ruling of 2026-10-19), on PostgreSQL 16, 17 and 18. Four statement shapes: S1 a point lookup, S2 the cross-tenant
 * probe (the other tenant's id, no row returned), S3 a read of 100 rows by primary key, S4 an aggregate over the
 * caller's rows. For S3 the p95 of the signed side stays within [RANGE_LIMIT] times the unsigned baseline and for S4
 * within [LIMIT] times; for S1 and S2 the signed p95 is at most [ADDED_P95_MICROS_MAX] microseconds above it. Each
 * shape runs on one connection: bind, one statement,
 * commit, and the three variants alternate iteration by iteration so drift hits all of them: U the unsigned baseline
 * table (its policy is the predicate of the releases before this task, the setting name is a constant so this file
 * holds no hand-written unsigned tenant read), X the same signed transaction (the HMAC is computed and the binding is
 * set) against a table whose policy calls a scratch function that returns the tenant from that setting with no
 * verification, S the signed table with the real `bound_tenant()`. X carries no assertion. The numbers and the
 * application-side signing time are printed. Stop rule: when a bound is exceeded the assertion fails and no constant
 * is changed.
 */
@Tag("integration")
class BindingPerformancePostgresIT {
    private val key = SignedBinding.key()
    private val actor = "bench-actor"
    private var warmup = WARMUP
    private var iterations = ITERATIONS

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    @EnabledIfSystemProperty(named = "inv.bench", matches = "true")
    fun `the signed binding stays within the two bounds of the unsigned baseline at p95`(image: String) {
        bench(image, assertBounds = true)
    }

    /**
     * Runs in every pipeline (the gate above needs `inv.bench`, so CI otherwise shows no passing case of this class):
     * the same harness at a tiny iteration count on one PostgreSQL version, asserting that all four shapes complete and
     * print their result rows. It asserts no timing bound, because a count this small says nothing about p95.
     */
    @Test
    fun `the harness completes S1 to S4 at a tiny iteration count`() {
        warmup = SMOKE_ITERATIONS
        iterations = SMOKE_ITERATIONS
        val results = bench("postgres:17", assertBounds = false)
        assertEquals(setOf("S1 point", "S2 cross-tenant", "S3 100 rows", "S4 aggregate"), results.keys)
        results.values.forEach { cells -> assertEquals(Variant.entries.toSet(), cells.keys) }
    }

    private fun bench(
        image: String,
        assertBounds: Boolean,
    ): Map<String, Map<Variant, Cell>> =
        PostgresProbeFixture(image, initSql = baselineTables()).use { fixture ->
            val pool =
                TestPoolFixture(
                    fixture.jdbcUrl(),
                    PostgresProbeFixture.APP,
                    PostgresProbeFixture.APP_PASSWORD,
                    maximumPoolSize = 1,
                    poolName = "bench",
                )
            pool.use { p -> p.dataSource().connection.use { run(it, image, assertBounds) } }
        }

    private fun run(
        connection: Connection,
        image: String,
        assertBounds: Boolean,
    ): Map<String, Map<Variant, Cell>> {
        val tenantA = UUID.randomUUID()
        val idsA = seed(connection, tenantA)
        val idsB = seed(connection, UUID.randomUUID())
        repeat(warmup) { i ->
            for (variant in Variant.entries) {
                lookup(
                    connection,
                    tenantA,
                    variant,
                    Query(POINT_SQL) { st, _ -> st.setObject(1, idsA[i % idsA.size]) },
                    i,
                )
            }
        }
        val results = shapes(connection, idsA, idsB).associate { it.name to measure(connection, tenantA, it.query()) }
        println("BENCH $image signing of SignedBinding.value per call: ${signingTime(tenantA)}")
        results.forEach { (name, cells) -> report(image, name, cells) }
        if (!assertBounds) return results
        val point = us(results, "S1 point")
        val cross = us(results, "S2 cross-tenant")
        val range = us(results, "S3 100 rows")
        val aggregate = us(results, "S4 aggregate")
        assertTrue(point.second - point.first <= ADDED_P95_MICROS_MAX, "point lookup p95 $point")
        assertTrue(cross.second - cross.first <= ADDED_P95_MICROS_MAX, "cross-tenant probe p95 $cross")
        assertTrue(range.second <= range.first * RANGE_LIMIT, "100-row read p95 $range")
        assertTrue(aggregate.second <= aggregate.first * LIMIT, "aggregate p95 $aggregate")
        return results
    }

    /** p95 of (U, S) of one shape. */
    private fun us(
        results: Map<String, Map<Variant, Cell>>,
        shape: String,
    ): Pair<Long, Long> = results.getValue(shape).let { it.getValue(Variant.U).p95 to it.getValue(Variant.S).p95 }

    private fun shapes(
        connection: Connection,
        idsA: List<UUID>,
        idsB: List<UUID>,
    ): List<Shape> =
        listOf(
            Shape("S1 point") { Query(POINT_SQL) { st, i -> st.setObject(1, idsA[i % idsA.size]) } },
            Shape("S2 cross-tenant") { Query(POINT_SQL) { st, i -> st.setObject(1, idsB[i % idsB.size]) } },
            Shape("S3 100 rows") {
                Query(RANGE_SQL) { st, i ->
                    val window = Array<UUID>(RANGE_ROWS) { idsA[(i + it) % idsA.size] }
                    st.setArray(1, connection.createArrayOf("uuid", window))
                }
            },
            Shape("S4 aggregate") { Query(AGGREGATE_SQL) { _, _ -> } },
        )

    private fun report(
        image: String,
        name: String,
        cells: Map<Variant, Cell>,
    ) {
        for (variant in Variant.entries) {
            val c = cells.getValue(variant)
            println("BENCH $image $name ${variant.label} p50=${c.p50}us p95=${c.p95}us")
        }
        val u = cells.getValue(Variant.U).p95
        val x = cells.getValue(Variant.X).p95
        val s = cells.getValue(Variant.S).p95
        val ratio = String.format(Locale.ROOT, "%.4f", s.toDouble() / u)
        println("BENCH $image $name p95 S-U=${s - u}us X-U=${x - u}us S-X=${s - x}us ratio S/U=$ratio")
    }

    private class Shape(
        val name: String,
        val query: () -> Query,
    )

    /** One statement and the binding of its parameters for iteration `i`. */
    private class Query(
        val sql: String,
        val bind: (PreparedStatement, Int) -> Unit,
    )

    private enum class Variant(
        val label: String,
        val table: String,
        val signed: Boolean,
    ) {
        U("unsigned", UNSIGNED_TABLE, false),
        X("stubbed", STUB_TABLE, true),
        S("signed", "probe", true),
    }

    private class Cell(
        val p50: Long,
        val p95: Long,
    )

    /** p50 and p95 of each variant in microseconds over [ITERATIONS] transactions that alternate U, X and S. */
    private fun measure(
        connection: Connection,
        tenant: UUID,
        query: Query,
    ): Map<Variant, Cell> {
        val nanos = Variant.entries.associateWith { LongArray(iterations) }
        for (i in 0 until iterations) {
            for (variant in Variant.entries) {
                nanos.getValue(variant)[i] = timed { lookup(connection, tenant, variant, query, i) }
            }
        }
        return nanos.mapValues { (_, v) -> Cell(percentile(v, 50), percentile(v, 95)) }
    }

    private fun signingTime(tenant: UUID): String {
        val nanos = LongArray(iterations)
        for (i in 0 until iterations) nanos[i] = timed { SignedBinding.value(key, TenantId(tenant), actor) }
        return "p50=${percentile(nanos, 50)}us p95=${percentile(nanos, 95)}us"
    }

    private inline fun timed(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return System.nanoTime() - start
    }

    private fun percentile(
        nanos: LongArray,
        percent: Int,
    ): Long = nanos.sorted()[(nanos.size * percent) / 100 - 1] / 1000

    private fun lookup(
        connection: Connection,
        tenant: UUID,
        variant: Variant,
        query: Query,
        iteration: Int,
    ) {
        connection.autoCommit = false
        bind(connection, tenant, variant.signed)
        connection.prepareStatement(query.sql.replace("{table}", "tenancy_kernel_test.${variant.table}")).use {
            query.bind(it, iteration)
            it.executeQuery().use { rs -> while (rs.next()) rs.getString(1) }
        }
        connection.commit()
    }

    private fun bind(
        connection: Connection,
        tenant: UUID,
        signed: Boolean,
    ) {
        if (signed) {
            val value = SignedBinding.value(key, TenantId(tenant), actor)
            connection
                .prepareStatement(
                    "select set_config('app.tenant_id', ?, true), set_config('app.actor', ?, true), " +
                        "set_config('app.binding_sig', ?, true)",
                ).use {
                    it.setString(1, tenant.toString())
                    it.setString(2, actor)
                    it.setString(3, value)
                    it.execute()
                }
        } else {
            connection
                .prepareStatement("select set_config('app.tenant_id', ?, true), set_config('app.actor', ?, true)")
                .use {
                    it.setString(1, tenant.toString())
                    it.setString(2, actor)
                    it.execute()
                }
        }
    }

    private fun seed(
        connection: Connection,
        tenant: UUID,
    ): List<UUID> {
        val ids = List(ROWS) { UUID.randomUUID() }
        for (variant in Variant.entries) {
            connection.autoCommit = false
            bind(connection, tenant, variant.signed)
            val table = variant.table
            connection
                .prepareStatement("insert into tenancy_kernel_test.$table (id, tenant_id, value) values (?, ?, ?)")
                .use { insert ->
                    ids.forEach {
                        insert.setObject(1, it)
                        insert.setObject(2, tenant)
                        insert.setString(3, "row-$it")
                        insert.addBatch()
                    }
                    insert.executeBatch()
                }
            connection.commit()
        }
        return ids
    }

    /** The unsigned baseline and the stubbed variant: no committed line holds a hand-written unsigned read. */
    private fun baselineTables(): String =
        """
        CREATE TABLE tenancy_kernel_test.$UNSIGNED_TABLE (
            id uuid PRIMARY KEY, tenant_id uuid NOT NULL, value text NOT NULL);
        ALTER TABLE tenancy_kernel_test.$UNSIGNED_TABLE ENABLE ROW LEVEL SECURITY;
        ALTER TABLE tenancy_kernel_test.$UNSIGNED_TABLE FORCE ROW LEVEL SECURITY;
        CREATE POLICY unsigned ON tenancy_kernel_test.$UNSIGNED_TABLE
            USING (tenant_id = nullif(current_setting('$TENANT_SETTING', true), '')::uuid)
            WITH CHECK (tenant_id = nullif(current_setting('$TENANT_SETTING', true), '')::uuid);
        CREATE FUNCTION tenancy_kernel_test.stub_tenant() RETURNS uuid
            LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog, tenancy_kernel_test, pg_temp AS ${'$'}${'$'}
        BEGIN
            RETURN nullif(current_setting('$TENANT_SETTING', true), '')::uuid;
        END;
        ${'$'}${'$'};
        CREATE TABLE tenancy_kernel_test.$STUB_TABLE (
            id uuid PRIMARY KEY, tenant_id uuid NOT NULL, value text NOT NULL);
        ALTER TABLE tenancy_kernel_test.$STUB_TABLE ENABLE ROW LEVEL SECURITY;
        ALTER TABLE tenancy_kernel_test.$STUB_TABLE FORCE ROW LEVEL SECURITY;
        CREATE POLICY stubbed ON tenancy_kernel_test.$STUB_TABLE
            USING (tenant_id = (select tenancy_kernel_test.stub_tenant()))
            WITH CHECK (tenant_id = (select tenancy_kernel_test.stub_tenant()));
        """.trimIndent()

    private companion object {
        const val UNSIGNED_TABLE = "probe_unsigned"
        const val STUB_TABLE = "probe_stubbed"
        const val POINT_SQL = "select value from {table} where id = ?"
        const val RANGE_SQL = "select value from {table} where id = any(?)"
        const val AGGREGATE_SQL = "select count(*), max(value) from {table}"
        const val RANGE_ROWS = 100
        const val TENANT_SETTING = "app.tenant_id"
        const val ROWS = 500
        const val WARMUP = 1_000
        const val ITERATIONS = 5_000
        const val SMOKE_ITERATIONS = 20
        const val LIMIT = 1.03
        const val ADDED_P95_MICROS_MAX = 200L
        const val RANGE_LIMIT = 1.06
    }
}
