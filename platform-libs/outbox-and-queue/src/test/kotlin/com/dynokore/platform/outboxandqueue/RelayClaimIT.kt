package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancyjooq.TableClass
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancyjooq.TenancyProbe
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * The claim reads of the relay and the unrelayed-event check (coordinator ruling INV-Q-5612): the sys-owned functions
 * `outbox_claim_page` and `outbox_claim_pending`, and the relay's run-spanning advisory lock in the outer platform
 * transaction (owner answer INV-Q-5609 (b)), on PostgreSQL 16, 17 and 18.
 */
@Tag("integration")
class RelayClaimIT {
    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `the claim functions return every tenant's rows, bounded by after_seq and page_size`(image: String) {
        OutboxHarness(image).use { harness ->
            val kit = harness.payload
            val seqs = plant(kit, 5)
            val (all, pending) =
                kit.inPlatformTx {
                    val page = OutboxClaimPage("payload").call(0, 100, false)
                    val rows =
                        kit.dsl
                            .select(page.seq)
                            .from(page)
                            .fetch(page.seq)
                    val count = DSL.function(DSL.name("payload", OutboxSchema.OUTBOX_CLAIM_PENDING), SQLDataType.BIGINT)
                    rows to
                        kit.dsl
                            .select(count)
                            .fetchOne()
                            ?.get(count)
                }
            assertEquals(seqs, all, "the platform session sees the rows of both tenants")
            assertEquals(5L, pending)
            val bounded =
                kit.inPlatformTx {
                    val page = OutboxClaimPage("payload").call(seqs[1], 2, false)
                    kit.dsl
                        .select(page.seq)
                        .from(page)
                        .fetch(page.seq)
                }
            assertEquals(listOf(seqs[2], seqs[3]), bounded, "after_seq 2 and page_size 2 give the seqs 3 and 4")
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `a login without EXECUTE is refused and the catalogue holds the contract`(image: String) {
        OutboxHarness(image).use { harness ->
            val fixture = harness.fixture
            val probe = TenancyProbe.connect(fixture.jdbcUrl(), fixture.adminUser(), fixture.adminPassword())
            val rows =
                probe
                    .fetchRows(
                        "select p.proname, pg_get_function_result(p.oid) as result, p.prosecdef, " +
                            "array_to_string(p.proconfig, ';') as config, " +
                            "exists (select 1 from aclexplode(p.proacl) a where a.grantee = 0) as public_grant, " +
                            "pg_get_userbyid(p.proowner) as owner " +
                            "from pg_proc p where p.pronamespace = 'payload'::regnamespace and p.proname in (?, ?)",
                        OutboxSchema.OUTBOX_CLAIM_PAGE,
                        OutboxSchema.OUTBOX_CLAIM_PENDING,
                    ).associateBy { it["proname"] }
            assertEquals(
                "TABLE(seq bigint, tenant_id uuid, published_at timestamp with time zone)",
                rows.getValue(OutboxSchema.OUTBOX_CLAIM_PAGE)["result"],
            )
            assertEquals("bigint", rows.getValue(OutboxSchema.OUTBOX_CLAIM_PENDING)["result"])
            rows.values.forEach {
                assertEquals(true, it["prosecdef"])
                assertEquals("search_path=pg_catalog, payload, pg_temp", it["config"])
                assertEquals(false, it["public_grant"])
                assertEquals(OutboxPostgresFixture.SYS, it["owner"])
            }
            val stranger = TenancyProbe.connect(fixture.jdbcUrl(), "recon_runtime", "recon_runtime_pw")
            val refused =
                runCatching { stranger.fetchValue("select payload.${OutboxSchema.OUTBOX_CLAIM_PENDING}()") }
                    .exceptionOrNull()
            assertEquals(
                "42501",
                generateSequence(refused) { it.cause }
                    .filterIsInstance<java.sql.SQLException>()
                    .firstOrNull()
                    ?.sqlState,
            )
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `while a row transaction of one relay runs a second relay publishes nothing and one backend holds the lock`(
        image: String,
    ) {
        OutboxHarness(image).use { harness ->
            val first = harness.payload
            val second = ServiceKit(harness, "payload", "payload-svc", harness.catalogue)
            plant(first, 2)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val holders = AtomicReference<Long>()
            val blocking =
                object : EventBinding {
                    override fun publish(
                        topic: String,
                        envelope: Envelope,
                    ) {
                        holders.set(advisoryHolders(second))
                        entered.countDown()
                        check(release.await(30, TimeUnit.SECONDS)) { "not released" }
                    }
                }
            val relay = JdbcOutboxRelay(first.scope, first.dsl, "payload", blocking, ServiceKit.CLOCK, "background", 2)
            val running = thread { relay.runOnce() }
            assertTrue(entered.await(30, TimeUnit.SECONDS))
            val other = second.relay.runOnce()
            assertEquals(0, other.published, "relay 2 publishes no row while relay 1 holds the lock")
            assertEquals(2L, other.pending)
            assertEquals(1L, advisoryHolders(second), "the lock is held by exactly one backend")
            release.countDown()
            running.join(30_000)
            assertEquals(1L, holders.get())
            assertEquals(0L, advisoryHolders(second), "no advisory lock remains after relay 1 returns")
            assertEquals(0L, harness.count("select count(*) from payload.outbox where published_at is not null"))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `a row that fails to publish does not release the lock and the run stops there`(image: String) {
        OutboxHarness(image).use { harness ->
            val kit = harness.payload
            val observer = ServiceKit(harness, "payload", "payload-svc", harness.catalogue)
            plant(kit, 3)
            val during = mutableListOf<Long>()
            val failing =
                object : EventBinding {
                    override fun publish(
                        topic: String,
                        envelope: Envelope,
                    ) {
                        during += advisoryHolders(observer)
                        throw EventBindingUnavailable("fixture outage", null)
                    }
                }
            val outcome =
                JdbcOutboxRelay(kit.scope, kit.dsl, "payload", failing, ServiceKit.CLOCK, "background", 2).runOnce()
            assertTrue(outcome.stalled)
            assertEquals(0, outcome.published)
            assertEquals(listOf(1L), during, "the lock is held while the row fails")
            assertEquals(3L, outcome.pending)
            assertEquals(0L, advisoryHolders(observer), "the lock ends with the outer transaction")
        }
    }

    /** Plants [count] rows over two tenants and returns their seqs in order. */
    private fun plant(
        kit: ServiceKit,
        count: Int,
    ): List<Long> {
        val tenants = listOf(UUID.randomUUID(), UUID.randomUUID())
        repeat(count) { index ->
            val tenant = tenants[index % 2]
            kit.inTx(tenant) {
                kit.writer.append(
                    listOf(OutboundEvent(TestCatalogue.PAYLOAD_READY, """{"n":$index}""")),
                    kit.docContext(tenant, UUID.randomUUID()),
                )
            }
        }
        return kit.inPlatformTx {
            val page = OutboxClaimPage("payload").call(0, 100, false)
            kit.dsl
                .select(page.seq)
                .from(page)
                .fetch(page.seq)
        }
    }

    /** The number of backends holding a granted advisory lock, read from `pg_catalog.pg_locks` through jOOQ. */
    private fun advisoryHolders(kit: ServiceKit): Long {
        val locks = DSL.name("pg_catalog", "pg_locks")
        val dsl = TenancyJooq.dslContext(mapOf(locks to TableClass.PLATFORM_SCOPED), kit.tenants, kit.boundary)
        val pid = DSL.field(DSL.name("pid"), SQLDataType.INTEGER)
        val locktype = DSL.field(DSL.name("locktype"), SQLDataType.CLOB)
        val granted = DSL.field(DSL.name("granted"), SQLDataType.BOOLEAN)
        val holders = DSL.countDistinct(pid)
        return kit.tenants.bindPlatform("svc:test/job:locks") {
            kit.boundary.inTransaction {
                checkNotNull(
                    dsl
                        .select(holders)
                        .from(DSL.table(locks))
                        .where(locktype.eq("advisory").and(granted))
                        .fetchOne(),
                ).get(holders).toLong()
            }
        }
    }
}
