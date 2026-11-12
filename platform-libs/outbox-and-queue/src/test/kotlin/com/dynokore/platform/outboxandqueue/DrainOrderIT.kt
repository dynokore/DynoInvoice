package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

/**
 * The drain order of the platform work queue (INV-REQ-554, INV-AC-20527): candidates are ordered by tier, rank (null
 * last), then enqueue position; the partition rule of INV-REQ-540 is untouched.
 */
@Tag("integration")
class DrainOrderIT {
    private val tenant = ServiceKit.TENANT
    private val rows =
        "select seq, partition_key, lane, state, drain_tier, drain_rank from submission.work_queue order by seq"

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-AC-20527 claim order is tier one then two then three, rank ascending, null rank last, then enqueue order`(
        image: String,
    ) {
        OutboxHarness(image).use { harness ->
            val q = QueueKit(harness)
            val order = listOf("t3-a", "t2-null", "t2-r5", "t2-r2", "t3-b", "t1")
            order.forEach { q.enqueue(tenant, it, Lane.LIVE) }
            assign(q, "t3-a", 3, null)
            assign(q, "t2-null", 2, null)
            assign(q, "t2-r5", 2, 5)
            assign(q, "t2-r2", 2, 2)
            assign(q, "t1", 1, 7)
            val claimed = q.drain().map { it.partitionKey }
            assertEquals(listOf("t1", "t2-r2", "t2-r5", "t2-null", "t3-a", "t3-b"), claimed)
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-AC-20527 the acceptance scenario claims P3 then P2 then P1 and P4`(image: String) {
        OutboxHarness(image).use { harness ->
            val q = QueueKit(harness)
            listOf("P1", "P2", "P3", "P4").forEach { q.enqueue(tenant, it, Lane.LIVE) }
            assign(q, "P1", 3, null)
            assign(q, "P2", 2, 5)
            assign(q, "P3", 1, 9, Lane.DEADLINE_NEAR)
            assertEquals(listOf("P3", "P2", "P1", "P4"), q.drain().map { it.partitionKey })
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-AC-20527 an item enqueued without assignment reads tier 3 and null rank`(image: String) {
        OutboxHarness(image).use { harness ->
            val q = QueueKit(harness)
            q.enqueue(tenant, "plain", Lane.LIVE)
            val row = harness.rowsIn(tenant, rows).single()
            assertEquals(3, (row.getValue("drain_tier") as Number).toInt())
            assertNull(row["drain_rank"])
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-AC-20527 a table built by the ddl of main then upgraded keeps its order and equals ddl, twice over`(
        image: String,
    ) {
        OutboxHarness(image).use { harness ->
            val fixture = harness.fixture
            val oldDdl = mainDdl("evidence")
            assertNotEquals(QueueSchema.ddl("evidence", APP, SYS), oldDdl, "the reconstruction removed the drain order")
            fixture.run(oldDdl)
            assertEquals(emptyList<String>(), columnNames(harness, "evidence").filter { it.startsWith("drain_") })
            listOf("p1", "p2", "p3", "p2", "p1").forEach {
                fixture.runAsAdmin(
                    "insert into evidence.work_queue (tenant_id, partition_key, lane, payload) " +
                        "values ('$tenant', '$it', 'LIVE', '{}')",
                )
            }
            val upgrade = QueueSchema.drainOrderUpgradeDdl("evidence", APP, SYS)
            fixture.run(upgrade)
            val once = snapshot(harness, "evidence")
            fixture.run(upgrade)
            assertEquals(once, snapshot(harness, "evidence"), "applying the upgrade twice changes nothing")
            assertEquals(snapshot(harness, "submission"), once, "upgrade and ddl yield the same objects")
            assertEquals(
                5,
                queryAsAdmin(
                    harness,
                    "select count(*) from evidence.work_queue where drain_tier = 3 and drain_rank is null",
                ).single().toInt(),
            )
            val claimed =
                (1..3).map {
                    queryAsAdmin(
                        harness,
                        "select claimed_partition from evidence.claim_due(" +
                            "'LIVE', null, now(), now() - interval '1 day')",
                    ).single()
                }
            assertEquals(listOf("p1", "p2", "p3"), claimed, "existing rows keep their order")
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-AC-20527 assignment counts changed items and leaves claimed items and other tenants alone`(image: String) {
        OutboxHarness(image).use { harness ->
            val q = QueueKit(harness)
            val other = UUID.randomUUID()
            val first = q.enqueue(tenant, "doc", Lane.LIVE)
            q.enqueue(tenant, "doc", Lane.LIVE)
            q.enqueue(tenant, "doc", Lane.LIVE)
            q.enqueue(other, "doc", Lane.LIVE)
            val claimed = notNull(q.queue.claimNext())
            assertEquals(first, claimed.seq)
            val otherBefore = harness.rowsIn(other, rows)

            val changed = assign(q, "doc", 1, 4, Lane.DEADLINE_NEAR)
            assertEquals(2, changed, "the two PENDING items of this tenant")
            val mine = harness.rowsIn(tenant, rows)
            assertEquals(listOf("LIVE", "DEADLINE_NEAR", "DEADLINE_NEAR"), mine.map { it["lane"] })
            assertEquals(listOf(3, 1, 1), mine.map { (it.getValue("drain_tier") as Number).toInt() })
            assertEquals(listOf(null, 4, 4), mine.map { it["drain_rank"] })
            assertEquals("CLAIMED", mine.first()["state"])
            assertEquals(otherBefore, harness.rowsIn(other, rows), "the other tenant's item is unchanged")

            val noLane = assign(q, "doc", 2, null)
            assertEquals(2, noLane)
            val lanes = harness.rowsIn(tenant, rows).map { it["lane"] }
            assertEquals(listOf("LIVE", "DEADLINE_NEAR", "DEADLINE_NEAR"), lanes)
            assertEquals(0, assign(q, "absent", 1, null))

            val platformChanged =
                q.kit.inPlatformTx { q.queue.assignDrainOrder("doc", 1, 1, Lane.REPLAY) }
            assertEquals(0, platformChanged, "the platform binding is not this tenant")
            assertEquals(otherBefore, harness.rowsIn(other, rows))
            assertEquals(mine.map { it["lane"] }, harness.rowsIn(tenant, rows).map { it["lane"] })

            // A superuser connection is not under row-level security: only the statement's own tenant condition
            // keeps the other tenant's item out.
            val unguarded = JdbcWorkQueue(q.scope, adminDsl(harness), "submission", q.limiter, q.clock)
            val viaAdmin =
                q.kit.tenants.bind(TenantId(tenant), "test-actor") { unguarded.assignDrainOrder("doc", 2, 9) }
            assertEquals(2, viaAdmin, "the two PENDING items of this tenant only")
            assertEquals(otherBefore, harness.rowsIn(other, rows), "no statement without the tenant condition")
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-AC-20527 a later item with tier 1 never overtakes the earlier item of its partition`(image: String) {
        OutboxHarness(image).use { harness ->
            val q = QueueKit(harness)
            val early = q.enqueue(tenant, "x", Lane.LIVE)
            val late = q.enqueue(tenant, "x", Lane.LIVE)
            q.enqueue(tenant, "y", Lane.LIVE)
            harness.fixture.runAsAdmin("update submission.work_queue set drain_tier = 1 where seq = $late")
            harness.fixture.runAsAdmin("update submission.work_queue set drain_tier = 3 where seq = $early")
            harness.fixture.runAsAdmin("update submission.work_queue set drain_tier = 2 where partition_key = 'y'")
            val claimed = q.drain()
            assertEquals(listOf("y", "x", "x"), claimed.map { it.partitionKey })
            assertEquals(listOf(early, late), claimed.filter { it.partitionKey == "x" }.map { it.seq })
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-AC-20527 a tier outside 1 to 3 or a negative rank is refused and changes no row`(image: String) {
        OutboxHarness(image).use { harness ->
            val q = QueueKit(harness)
            q.enqueue(tenant, "doc", Lane.LIVE)
            val before = harness.rowsIn(tenant, rows)
            listOf(0 to null, 4 to null, 2 to -1).forEach { (tier, rank) ->
                assertThrows(IllegalArgumentException::class.java) { assign(q, "doc", tier, rank, Lane.REPLAY) }
                assertEquals(before, harness.rowsIn(tenant, rows), "tier $tier rank $rank")
            }
        }
    }

    private fun assign(
        q: QueueKit,
        partition: String,
        tier: Int,
        rank: Int?,
        lane: Lane? = null,
    ): Int = q.kit.inTx(tenant) { q.queue.assignDrainOrder(partition, tier, rank, lane) }

    /** The DDL of platform-libs 6.20.0, rebuilt from this branch's text by removing what this task added. */
    private fun mainDdl(schema: String): String =
        QueueSchema
            .ddl(schema, APP, SYS)
            .replace(",\n    drain_tier smallint not null default 3 check (drain_tier in (1, 2, 3)),", ",")
            .replace(",\n    drain_rank int\n", "\n")
            .replace(", drain_tier,\n    drain_rank)", ")")
            .replace("order by q.drain_tier, q.drain_rank nulls last, q.seq", "order by q.seq")

    private fun columnNames(
        harness: OutboxHarness,
        schema: String,
    ): List<String> =
        queryAsAdmin(
            harness,
            "select column_name from information_schema.columns where table_schema = '$schema' " +
                "and table_name = 'work_queue' order by ordinal_position",
        )

    /** Columns, sys column grants and the claim function of [schema], with the schema name made neutral. */
    private fun snapshot(
        harness: OutboxHarness,
        schema: String,
    ): List<String> {
        val f = harness.fixture
        val columns =
            queryAsAdmin(
                harness,
                "select column_name || '|' || data_type || '|' || coalesce(column_default, '') || '|' || is_nullable " +
                    "|| '|' || ordinal_position from information_schema.columns where table_schema = '$schema' " +
                    "and table_name = 'work_queue' order by ordinal_position",
            )
        val grants =
            queryAsAdmin(
                harness,
                "select grantee || '|' || column_name || '|' || privilege_type " +
                    "from information_schema.column_privileges where table_schema = '$schema' " +
                    "and table_name = 'work_queue' and grantee = '$SYS' order by 1",
            )
        val function =
            queryAsAdmin(
                harness,
                "select pg_get_functiondef('$schema.claim_due(text,text,timestamptz,timestamptz)'::regprocedure)",
            )
        val checks =
            queryAsAdmin(
                harness,
                "select pg_get_constraintdef(c.oid) from pg_constraint c " +
                    "where c.conrelid = '$schema.work_queue'::regclass and c.contype = 'c' order by 1",
            )
        assertTrue(function.single().contains("drain_tier"))
        return (columns + grants + function + checks).map { it.replace(schema, "S") }
    }

    private fun adminDsl(harness: OutboxHarness) = DSL.using(harness.fixture.adminDataSource(), SQLDialect.POSTGRES)

    private fun queryAsAdmin(
        harness: OutboxHarness,
        sql: String,
    ): List<String> =
        harness.fixture.adminDataSource().connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rs ->
                    generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
                }
            }
        }

    private companion object {
        const val APP = OutboxPostgresFixture.APP
        const val SYS = OutboxPostgresFixture.SYS
    }
}
