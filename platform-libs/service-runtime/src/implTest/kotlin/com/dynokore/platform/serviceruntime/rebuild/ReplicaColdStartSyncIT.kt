package com.dynokore.platform.serviceruntime.rebuild

import com.dynokore.platform.auditlog.OutboxAuditWriter
import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventLogSchema
import com.dynokore.platform.outboxandqueue.JdbcOutboxWriter
import com.dynokore.platform.outboxandqueue.OutboxPostgresFixture
import com.dynokore.platform.outboxandqueue.OutboxSchema
import com.dynokore.platform.outboxandqueue.ReadModelClear
import com.dynokore.platform.outboxandqueue.ReadModelTable
import com.dynokore.platform.outboxandqueue.ReplayMode
import com.dynokore.platform.outboxandqueue.ReplayProtocol
import com.dynokore.platform.outboxandqueue.ReplayRequestTopic
import com.dynokore.platform.outboxandqueue.ReplayScope
import com.dynokore.platform.outboxandqueue.ReplayTopic
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.testing.TestDatabase
import com.dynokore.platform.tenancyjooq.TableClass
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TenantRlsMigration
import org.jooq.Condition
import org.jooq.Field
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.context.event.ApplicationStartedEvent
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.event.ContextRefreshedEvent
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.sql.DriverManager
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import java.util.function.Supplier

/**
 * ReplicaColdStartSync (Keel INV-T-9057; ruling INV-Q-9047 A; INV-REQ-1201) on PostgreSQL 17 and 18: one FULL_SYNC
 * request to access-svc with the system audit entry, the repeat rule, foreign replays, and the look-alikes that must
 * write nothing or keep failing (rebuildConsumer's EVL-002 and EVL-003).
 */
@Tag("integration")
class ReplicaColdStartSyncIT {
    private val mapper = ObjectMapper()

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a ready service with Unknown lag writes one FULL_SYNC request to access-svc and one system audit entry`(
        image: String,
    ) {
        Harness(image).use { h ->
            val sync = h.coldStart()
            val done = sync.sync() as RebuildOutcome.Done
            assertTrue(done.created)
            assertTrue(sync.isOwnReplay(done.state.replayId))
            val requested = h.data(ReplayProtocol.REPLAY_REQUESTED).single()
            assertEquals("FULL_SYNC", requested.get("mode").asString())
            assertEquals(ACCESS, requested.get("producer").asString())
            assertEquals(done.state.replayId.toString(), requested.get("replayId").asString())
            val audit = h.data(OutboxAuditWriter.EVENT_TYPE).single()
            assertEquals("system:replica-cold-start", audit.get("actor").asString())
            assertEquals(done.state.replayId.toString(), audit.get("reason").asString())
            assertEquals(1L, h.count("consumer_replay"))
            // a second start-up call while its own replay is pending writes nothing (repeat)
            val repeat = sync.sync() as RebuildOutcome.Done
            assertFalse(repeat.created)
            assertEquals(done.state.replayId, repeat.state.replayId)
            assertTrue(sync.isOwnReplay(done.state.replayId))
            assertEquals(2L, h.count("outbox"))
            assertEquals(1L, h.count("consumer_replay"))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a pending replay created by another process is foreign and the component requests again after it completes`(
        image: String,
    ) {
        Harness(image).use { h ->
            val earlier = h.coldStart()
            val first = earlier.sync() as RebuildOutcome.Done
            val mine = h.coldStart()
            val answer = mine.sync() as RebuildOutcome.Done
            assertFalse(answer.created)
            assertEquals(first.state.replayId, answer.state.replayId)
            assertFalse(mine.isOwnReplay(first.state.replayId), "foreign")
            assertTrue(earlier.isOwnReplay(first.state.replayId))
            assertEquals(2L, h.count("outbox"), "nothing written for the foreign replay")
            h.completeAll()
            val again = mine.sync() as RebuildOutcome.Done
            assertTrue(again.created)
            assertTrue(mine.isOwnReplay(again.state.replayId))
            assertFalse(mine.isOwnReplay(first.state.replayId))
            assertEquals(2L, h.count("consumer_replay"))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a measured lag, or a service that does not subscribe to the topic, writes nothing`(image: String) {
        Harness(image).use { h ->
            val measured = AuthorityReplica(Clock.systemUTC())
            measured.tracker.onSnapshotComplete(1)
            measured.tracker.observeHead(1)
            assertNull(h.coldStart(replica = measured).sync())
            val lookAlike = h.coldStart(subscribed = false).sync()
            assertEquals(RebuildOutcome.Refused(RebuildErrorCode.TOPIC_NOT_CONSUMED), lookAlike)
            assertEquals(0L, h.count("outbox"))
            assertEquals(0L, h.count("consumer_replay"))
            assertNull(h.nextOffsetOrNull())
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `rebuildConsumer keeps EVL-002 for a non-operator and EVL-003 for a topic not in rebuild required`(
        image: String,
    ) {
        Harness(image).use { h ->
            h.behind(5)
            assertEquals(
                RebuildOutcome.Refused(RebuildErrorCode.ROLE_NOT_PERMITTED),
                h.runtime().rebuildConsumer(h.request("system:replica-cold-start")),
            )
            assertEquals(0L, h.count("outbox"))
            h.deleteWatermark()
            assertEquals(
                RebuildOutcome.Refused(RebuildErrorCode.NOT_REBUILD_REQUIRED),
                h.runtime().rebuildConsumer(h.request("platform.engineer")),
            )
            assertEquals(0L, h.count("outbox"))
            assertNotNull(h.coldStart().sync(), "the cold start needs no rebuild-required state")
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `the ready event writes exactly one request and one audit entry, and no other event does`(image: String) {
        Harness(image).use { h ->
            // look-alikes: a context that never sees the ready event, with the refresh and started events published
            AnnotationConfigApplicationContext().use { ctx ->
                ctx.registerBean(ReplicaColdStartSync::class.java, Supplier { h.coldStart() })
                ctx.refresh()
                ctx.publishEvent(ApplicationStartedEvent(SpringApplication(), emptyArray(), ctx, Duration.ZERO))
                assertEquals(0, h.fullSyncRequests())
                assertEquals(0, h.coldStartAudits())
                assertFalse(ctx.getBean(ReplicaColdStartSync::class.java).isRunning)
            }
            val sync = h.coldStart()
            val before = ArrayList<Pair<Int, Int>>()
            val context =
                SpringApplicationBuilder(EmptyApplication::class.java)
                    .web(WebApplicationType.NONE)
                    .initializers({ ctx ->
                        ctx.addApplicationListener { e ->
                            if (e !is ContextRefreshedEvent &&
                                e !is ApplicationStartedEvent
                            ) {
                                return@addApplicationListener
                            }
                            before += h.fullSyncRequests() to h.coldStartAudits()
                        }
                        (ctx as org.springframework.context.support.GenericApplicationContext)
                            .registerBean(ReplicaColdStartSync::class.java, Supplier { sync })
                    })
                    .run()
            context.use { ctx ->
                assertTrue(
                    before.size >= 2 && before.all { it == 0 to 0 },
                    "nothing written before the ready event: $before",
                )
                assertEquals(1, h.fullSyncRequests())
                assertEquals(1, h.coldStartAudits())
                assertTrue(sync.isRunning)
                ctx.publishEvent(ApplicationReadyEvent(SpringApplication(), emptyArray(), ctx, Duration.ZERO))
                assertEquals(1, h.fullSyncRequests())
                assertEquals(1, h.coldStartAudits())
            }
            assertFalse(sync.isRunning, "stopped with the context")
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `the cold start passes only the empty clear of access-svc and never the service's own clears`(image: String) {
        val table = ReadModelTable("match_view", CONSUMER)
        val own = listOf(ReadModelClear(ACCESS, listOf(table)))
        val lookAlike = listOf(ReadModelClear("access-svc-2", listOf(table)), ReadModelClear(ACCESS, listOf(table)))
        listOf(own, lookAlike).forEach { clears ->
            Harness(image).use { h ->
                h.insert("match_view", CONSUMER, TENANT.value, "n")
                val done = h.coldStart(clears = clears).sync() as RebuildOutcome.Done
                assertTrue(done.created)
                assertEquals(1L, h.count("match_view"), "the cold start clears no table")
                assertEquals(1, h.fullSyncRequests())
                assertEquals(1, h.coldStartAudits())
                // the operator path keeps the own clears: the same table is cleared there
                h.completeAll()
                h.behind(5)
                h.rebuildFullSync(clears)
                assertEquals(0L, h.count("match_view"), "rebuildConsumer clears the own table")
            }
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `the scheduled re-check waits for a foreign replay, requests after it, survives a failure and stops`(
        image: String,
    ) {
        Harness(image).use { h ->
            val first = h.coldStart().sync() as RebuildOutcome.Done
            val mine = h.coldStart(interval = Duration.ofMillis(50))
            assertTrue((mine.sync() as RebuildOutcome.Done).state.replayId == first.state.replayId)
            assertFalse(mine.isOwnReplay(first.state.replayId))
            assertFalse(mine.isRunning)
            mine.start()
            assertTrue(mine.isRunning)
            Thread.sleep(400)
            assertEquals(1, h.fullSyncRequests(), "a pending foreign replay: runs write nothing")
            // the replica or database fails once: the run is swallowed and the next run still happens
            h.completeAll()
            h.rename("consumer_replay", "consumer_replay_off")
            Thread.sleep(300)
            assertTrue(mine.isRunning)
            h.rename("consumer_replay_off", "consumer_replay")
            h.awaitRequests(2)
            assertEquals(2, h.fullSyncRequests(), "the first run after completion adds exactly one")
            mine.stop()
            assertFalse(mine.isRunning)
            h.completeAll()
            Thread.sleep(300)
            assertEquals(2, h.fullSyncRequests(), "no run after stop")
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a service subscribed only to the retired topic writes nothing`(image: String) {
        Harness(image).use { h ->
            val retired = h.coldStart(subscribed = false)
            assertEquals(RebuildOutcome.Refused(RebuildErrorCode.TOPIC_NOT_CONSUMED), retired.sync())
            assertEquals(0, h.fullSyncRequests())
            assertEquals(0, h.coldStartAudits())
        }
    }

    private class EmptyApplication

    private inner class Harness(
        image: String,
    ) : AutoCloseable {
        val fixture =
            OutboxPostgresFixture(
                image,
                OutboxPostgresFixture.serviceInitSql(SCHEMA, MATCH_VIEW_DDL) +
                    OutboxPostgresFixture.eventLogInitSql(CATALOGUE),
            )
        private val db =
            TestDatabase(
                fixture.jdbcUrl(),
                (
                    listOf("outbox", "consumer_replay", "consumer_offset", "consumer_sequence", "inbox", "match_view")
                        .map { "$SCHEMA.$it" } + listOf("eventlog.purge_watermark")
                ).associateWith { TableClass.PLATFORM_SCOPED },
            )
        private val admin = db.login(fixture.adminUser(), fixture.adminPassword())
        private val app = db.login(OutboxPostgresFixture.APP, OutboxPostgresFixture.APP_PASSWORD)
        private val outbox =
            JdbcOutboxWriter(
                TenancyJooq.dslContext(OutboxSchema.jooqTables(SCHEMA), db.tenants, app.runner),
                SCHEMA,
                CONSUMER,
                "$CONSUMER-1",
                CATALOGUE,
                Clock.systemUTC(),
            )

        fun runtime(
            subscribed: Boolean = true,
            clears: List<ReadModelClear>? = null,
        ) = ConsumerRebuildRuntime(
            CONSUMER,
            SCHEMA,
            CATALOGUE,
            if (subscribed) listOf(ConsumerSubscription(CONSUMER, AUTHORITY, SCHEMA)) else emptyList(),
            TenantScope(db.tenants, app.runner, app.runner, OutboxPostgresFixture.WORKLOAD),
            outbox,
            OutboxAuditWriter(outbox),
            fixture.eventLogReader(),
            { it == "platform.engineer" },
            Clock.systemUTC(),
            clears?.let { FullSyncWiring(it) },
        )

        fun coldStart(
            replica: AuthorityReplica = AuthorityReplica(Clock.systemUTC()),
            subscribed: Boolean = true,
            clears: List<ReadModelClear>? = null,
            interval: Duration = ReplicaColdStartSync.DEFAULT_RECHECK_INTERVAL,
        ) = ReplicaColdStartSync(runtime(subscribed, clears), replica, interval)

        fun insert(
            table: String,
            vararg values: Any?,
        ) {
            admin.asPlatform { insertInto(tbl(table)).values(*values).execute() }
        }

        fun rename(
            from: String,
            to: String,
        ) {
            DriverManager.getConnection(fixture.jdbcUrl(), fixture.adminUser(), fixture.adminPassword()).use { c ->
                c.createStatement().use { it.execute("alter table $SCHEMA.$from rename to $to") }
            }
        }

        /** The ReplayRequested rows of mode FULL_SYNC addressed to access-svc. */
        fun fullSyncRequests(): Int =
            data(ReplayProtocol.REPLAY_REQUESTED).count {
                it.get("mode").asString() == "FULL_SYNC" && it.get("producer").asString() == ACCESS
            }

        fun coldStartAudits(): Int =
            data(OutboxAuditWriter.EVENT_TYPE).count {
                it.get("actor").asString() == "system:replica-cold-start"
            }

        fun request(actor: String) =
            RebuildConsumerRequest(
                actor,
                AUTHORITY,
                ReplayMode.SCOPE,
                ReplayScope.Tenant(TenantId(UUID.randomUUID())),
                UUID.randomUUID(),
                null,
                CorrelationId("corr-1"),
                CausationId("op-1"),
            )

        fun behind(watermark: Long) {
            admin.asPlatform {
                insertInto(tbl("eventlog.purge_watermark"))
                    .values(EventLogSchema.tableName(AUTHORITY), watermark, OffsetDateTime.now())
                    .execute()
            }
        }

        fun deleteWatermark() {
            admin.asPlatform { deleteFrom(tbl("eventlog.purge_watermark")).execute() }
            admin.asPlatform { deleteFrom(tbl("consumer_offset")).execute() }
        }

        fun awaitRequests(n: Int) {
            val deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos()
            while (fullSyncRequests() < n && System.nanoTime() < deadline) Thread.sleep(50)
        }

        fun rebuildFullSync(clears: List<ReadModelClear>) {
            val r = runtime(clears = clears)
            val request =
                RebuildConsumerRequest(
                    "platform.engineer",
                    AUTHORITY,
                    ReplayMode.FULL_SYNC,
                    null,
                    UUID.randomUUID(),
                    "operator",
                    CorrelationId("corr-op"),
                    CausationId("op-1"),
                )
            assertTrue(r.rebuildConsumer(request) is RebuildOutcome.Done)
        }

        fun completeAll() {
            admin.asPlatform {
                update(tbl("consumer_replay"))
                    .set(f("completed_at"), DSL.currentOffsetDateTime())
                    .set(f("closed_at"), DSL.currentOffsetDateTime())
                    .execute()
            }
        }

        private fun tbl(name: String) = DSL.table(DSL.name(if ('.' in name) name.split('.') else listOf(SCHEMA, name)))

        fun count(
            table: String,
            vararg where: Condition,
        ): Long = admin.asPlatform { fetchCount(tbl(table), DSL.and(*where)) }.toLong()

        fun nextOffsetOrNull(): Long? =
            admin.asPlatform {
                select(f("next_offset"))
                    .from(tbl("consumer_offset"))
                    .where(f("consumer").eq(CONSUMER).and(f("topic").eq(AUTHORITY)))
                    .fetchOne(0, Long::class.java)
            }

        fun data(type: String): List<JsonNode> =
            admin.asPlatform {
                select(DSL.field(DSL.name("data"), SQLDataType.JSONB))
                    .from(tbl("outbox"))
                    .where(f("type").eq(type))
                    .orderBy(f("seq"))
                    .fetch()
                    .map { mapper.readTree(it.value1().data()) }
            }

        override fun close() {
            db.close()
            fixture.close()
        }
    }

    private companion object {
        const val SCHEMA = "reconciliation"
        const val CONSUMER = "reconciliation-svc"
        const val ACCESS = "access-svc"
        const val AUTHORITY = ReplicaColdStartSync.TOPIC
        val TENANT = TenantId(UUID.fromString("0a000000-0000-0000-0000-00000000000a"))

        val MATCH_VIEW_DDL =
            """
            create table $SCHEMA.match_view (consumer text not null, tenant_id uuid not null, note text not null);
            ${TenantRlsMigration.ddl(SCHEMA, "match_view", policyName = "match_view_tenant_isolation")}
            ${OutboxSchema.readModelClearFunction(
                SCHEMA,
                "match_view",
                OutboxPostgresFixture.APP,
                OutboxPostgresFixture.SYS,
                "consumer",
            )}
            """.trimIndent()

        private fun f(name: String): Field<Any> = DSL.field(DSL.name(name))

        val CATALOGUE =
            EventCatalogue(
                listOf(
                    CatalogueEntry(
                        "com.dynokore.gst.platform.AuthorityChanged.v1",
                        AUTHORITY,
                        setOf(ACCESS),
                        false,
                        retainedWhileResolvable = false,
                    ),
                    CatalogueEntry(
                        OutboxAuditWriter.EVENT_TYPE,
                        "gst.platform.audit-entry-recorded.v1",
                        setOf(CatalogueEntry.EVERY_SERVICE),
                        false,
                        retainedWhileResolvable = false,
                    ),
                ),
                listOf(
                    ReplayTopic(
                        "gst.access.replay-reconciliation.v1",
                        setOf("com.dynokore.gst.platform.AuthorityChanged.v1", ReplayProtocol.REPLAY_COMPLETED),
                        ACCESS,
                        CONSUMER,
                        false,
                        retainedWhileResolvable = false,
                    ),
                ),
                listOf(ReplayRequestTopic("gst.reconciliation.replay-requested.v1", CONSUMER, setOf(ACCESS))),
            )
    }
}
