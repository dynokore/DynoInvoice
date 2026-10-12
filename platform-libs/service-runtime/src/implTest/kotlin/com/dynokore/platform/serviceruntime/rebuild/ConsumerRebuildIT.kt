package com.dynokore.platform.serviceruntime.rebuild

import com.dynokore.platform.auditlog.AuditEntry
import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.auditlog.OutboxAuditWriter
import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.EventLogSchema
import com.dynokore.platform.outboxandqueue.JdbcOutboxWriter
import com.dynokore.platform.outboxandqueue.OutboxPostgresFixture
import com.dynokore.platform.outboxandqueue.OutboxSchema
import com.dynokore.platform.outboxandqueue.PostgresEventLogReader
import com.dynokore.platform.outboxandqueue.ReadModelClear
import com.dynokore.platform.outboxandqueue.ReadModelTable
import com.dynokore.platform.outboxandqueue.ReplayJson
import com.dynokore.platform.outboxandqueue.ReplayMode
import com.dynokore.platform.outboxandqueue.ReplayProtocol
import com.dynokore.platform.outboxandqueue.ReplayRequestTopic
import com.dynokore.platform.outboxandqueue.ReplayScope
import com.dynokore.platform.outboxandqueue.ReplayTopic
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TenantRlsMigration
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.util.UUID

/**
 * rebuildConsumer (Keel INV-T-3385; INV-API-3656; INV-REQ-1205 b4, b6; rulings INV-Q-3599, INV-Q-3663, INV-Q-3665,
 * INV-Q-3667, INV-Q-3788; INV-AC-3657, INV-AC-3817, INV-TS-3818) on PostgreSQL 17 and 18: the error codes in their
 * order, the head move, ReplayRequested and audit entry in one transaction, the repeat rule, and a topic with several
 * producers addressed to all of them under one replay id.
 */
@Tag("integration")
class ConsumerRebuildIT {
    private val mapper = ObjectMapper()

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `EVL-001, EVL-002 and EVL-003 refuse and write nothing`(image: String) {
        Harness(image).use { h ->
            assertEquals(refused(RebuildErrorCode.TOPIC_NOT_CONSUMED), h.runtime().rebuildConsumer(h.request(topic = "gst.other.thing.v1")))
            assertEquals(refused(RebuildErrorCode.ROLE_NOT_PERMITTED), h.runtime().rebuildConsumer(h.request(actor = "tenant.admin")))
            assertEquals(refused(RebuildErrorCode.NOT_REBUILD_REQUIRED), h.runtime().rebuildConsumer(h.request()), "no watermark yet")
            assertEquals(0L, h.count("select count(*) from $SCHEMA.outbox"))
            assertEquals(0L, h.count("select count(*) from $SCHEMA.consumer_replay"))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `INV-AC-3657 the head move, the request and the audit entry commit together, a repeat writes nothing`(image: String) {
        Harness(image).use { h ->
            h.behind(watermark = 5)
            h.fixture.captureActors("$SCHEMA.outbox")
            val request = h.request()
            val done = h.runtime().rebuildConsumer(request) as RebuildOutcome.Done
            assertTrue(done.created)
            assertFalse(done.state.repeat)
            assertEquals(listOf(LIVE, PRODUCER, ReplayMode.SCOPE), listOf(done.state.topic, done.state.producer, done.state.mode))
            assertEquals(listOf(PRODUCER), done.state.producers)
            assertEquals(6L, h.nextOffset(), "moved past max(head, watermark)")
            assertEquals(
                setOf("platform.engineer"),
                h.fixture.capturedActors("$SCHEMA.outbox").toSet(),
                "the operator's transaction is bound to the operator, never the service (INV-Q-4268)",
            )

            val requested = h.data(ReplayProtocol.REPLAY_REQUESTED).single()
            assertEquals(done.state.replayId.toString(), requested.get("replayId").asString())
            assertEquals(PRODUCER, requested.get("producer").asString())
            val audit = h.data(OutboxAuditWriter.EVENT_TYPE).single()
            assertEquals("platform.engineer", audit.get("actor").asString())
            assertEquals("rebuildConsumer", audit.get("action").asString())
            assertEquals("Idempotency-Key ${request.idempotencyKey}", audit.get("reason").asString())
            assertEquals(1L, audit.get("priorState").get("positions").get(0).get("nextOffset").asLong())
            assertEquals(LIVE, mapper.readTree(audit.get("scope").asString()).get("topic").asString())

            // A repeat for the pair with the same mode and scope: the pending replay, nothing written (INV-Q-3665).
            val repeat = h.runtime().rebuildConsumer(h.request()) as RebuildOutcome.Done
            assertFalse(repeat.created)
            assertTrue(repeat.state.repeat)
            assertEquals(done.state.replayId, repeat.state.replayId)
            assertEquals(2L, h.count("select count(*) from $SCHEMA.outbox"))
            // ...another scope while it is pending: EVL-004.
            val other = h.request(scope = ReplayScope.Tenant(TenantId(UUID.randomUUID())))
            assertEquals(refused(RebuildErrorCode.OTHER_REPLAY_PENDING), h.runtime().rebuildConsumer(other))
            assertEquals(2L, h.count("select count(*) from $SCHEMA.outbox"))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a failing audit write leaves no head move and no request`(image: String) {
        Harness(image).use { h ->
            h.behind(watermark = 5)
            val failing =
                object : AuditWriter {
                    override fun record(
                        entry: AuditEntry,
                        context: EventContext,
                    ): Envelope = error("audit store down")
                }
            assertThrows(IllegalStateException::class.java) { h.runtime(audit = failing).rebuildConsumer(h.request()) }
            assertNull(h.nextOffsetOrNull())
            assertEquals(0L, h.count("select count(*) from $SCHEMA.outbox"))
            assertEquals(0L, h.count("select count(*) from $SCHEMA.consumer_replay"))
            assertNotNull(h.runtime().rebuildConsumer(h.request()) as? RebuildOutcome.Done, "a later call succeeds")
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-Q-3786 a full sync clears both tenants' rows of the producer on the runtime login in one transaction`(image: String) {
        Harness(image).use { h ->
            h.behind(watermark = 5)
            h.matchView()
            val other = TenantId(UUID.randomUUID())
            for (tenant in listOf(TENANT, other)) {
                h.fixture.runAsAdmin(
                    "insert into $SCHEMA.match_view values ('$CONSUMER', '${tenant.value}', 'n');" +
                        "insert into $SCHEMA.consumer_sequence values ('$CONSUMER', '/gst/$PRODUCER', 's', '${tenant.value}', 4);" +
                        "insert into $SCHEMA.consumer_sequence values ('$CONSUMER', '/gst/other-svc', 's', '${tenant.value}', 4);" +
                        "insert into $SCHEMA.inbox (consumer, event_id, tenant_id) values ('$CONSUMER', 'e-${tenant.value}', '${tenant.value}')",
                )
            }
            val full = h.request(mode = ReplayMode.FULL_SYNC, scope = null)
            val done = h.runtime(fullSync = true).rebuildConsumer(full) as RebuildOutcome.Done
            assertTrue(done.created)
            assertEquals(ReplayMode.FULL_SYNC, done.state.mode)
            assertEquals(0L, h.count("select count(*) from $SCHEMA.match_view"), "the read model of both tenants cleared")
            assertEquals(
                listOf("/gst/other-svc", "/gst/other-svc"),
                h.strings("select source from $SCHEMA.consumer_sequence order by source"),
                "the producer's docsequence rows of both tenants deleted, another producer's kept",
            )
            assertEquals(2L, h.count("select count(*) from $SCHEMA.inbox"), "the inbox kept")
            assertEquals(6L, h.nextOffset())
            assertEquals("FULL_SYNC", h.data(ReplayProtocol.REPLAY_REQUESTED).single().get("mode").asString())
            assertEquals(1, h.data(OutboxAuditWriter.EVENT_TYPE).size)
            assertEquals(listOf(TenantId.PLATFORM.value.toString()), h.strings("select distinct tenant_id::text from $SCHEMA.outbox"))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a failing audit write in a full sync leaves the rows and the position as they were`(image: String) {
        Harness(image).use { h ->
            h.behind(watermark = 5)
            h.matchView()
            h.fixture.runAsAdmin(
                "insert into $SCHEMA.match_view values ('$CONSUMER', '${TENANT.value}', 'n');" +
                    "insert into $SCHEMA.consumer_sequence values ('$CONSUMER', '/gst/$PRODUCER', 's', '${TENANT.value}', 4)",
            )
            val failing =
                object : AuditWriter {
                    override fun record(
                        entry: AuditEntry,
                        context: EventContext,
                    ): Envelope = error("audit store down")
                }
            val full = h.request(mode = ReplayMode.FULL_SYNC, scope = null)
            assertThrows(IllegalStateException::class.java) { h.runtime(fullSync = true, syncAudit = failing).rebuildConsumer(full) }
            assertEquals(1L, h.count("select count(*) from $SCHEMA.consumer_sequence"))
            assertEquals(1L, h.count("select count(*) from $SCHEMA.match_view"))
            assertNull(h.nextOffsetOrNull())
            assertEquals(0L, h.count("select count(*) from $SCHEMA.outbox"))
            assertEquals(0L, h.count("select count(*) from $SCHEMA.consumer_replay"))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-AC-3817 one call on a topic with several producers addresses all under one replay id`(image: String) {
        Harness(image).use { h ->
            h.behind(watermark = 5, topic = MULTI)
            val request = h.request(topic = MULTI)
            val done = h.runtime().rebuildConsumer(request) as RebuildOutcome.Done
            assertTrue(done.created)
            assertEquals(listOf("a-svc", "b-svc"), done.state.producers)
            assertEquals(ReplayState.SEVERAL_PRODUCERS, done.state.producer)
            assertEquals(listOf(MULTI), h.strings("select topic from $SCHEMA.consumer_offset"), "one head move, this topic only")
            assertEquals(6L, h.nextOffset(MULTI))
            val requested = h.data(ReplayProtocol.REPLAY_REQUESTED)
            assertEquals(listOf("a-svc", "b-svc"), requested.map { it.get("producer").asString() }.sorted(), "one per producer")
            assertEquals(setOf(done.state.replayId.toString()), requested.map { it.get("replayId").asString() }.toSet())
            val scope = mapper.readTree(h.data(OutboxAuditWriter.EVENT_TYPE).single().get("scope").asString())
            assertEquals(listOf("a-svc", "b-svc"), scope.get("producers").let { a -> (0 until a.size()).map { a.get(it).asString() } }, "the audit names every addressee")
            assertEquals(3L, h.count("select count(*) from $SCHEMA.outbox"))

            // The repeat rule over the set of pairs: the pending replay, nothing written; also once one pair completed,
            // since the replay stays pending until its incident closes (coordinator ruling INV-Q-3845).
            for (step in listOf("both pending", "a-svc completed")) {
                val repeat = h.runtime().rebuildConsumer(h.request(topic = MULTI)) as RebuildOutcome.Done
                assertEquals(listOf(false, true), listOf(repeat.created, repeat.state.repeat), step)
                assertEquals(done.state.replayId, repeat.state.replayId, step)
                assertEquals(done.state.producers, repeat.state.producers, step)
                h.fixture.runAsAdmin("update $SCHEMA.consumer_replay set completed_at = now() where producer = 'a-svc'")
            }
            val other = h.request(topic = MULTI, scope = ReplayScope.Tenant(TenantId(UUID.randomUUID())))
            assertEquals(refused(RebuildErrorCode.OTHER_REPLAY_PENDING), h.runtime().rebuildConsumer(other))
            // Another replay (same mode and scope) pending on one of the pairs only is EVL-004 too.
            val scopeJson = ReplayJson().writeScope(ReplayScope.Tenant(TENANT))
            h.fixture.runAsAdmin(
                "delete from $SCHEMA.consumer_replay where producer = 'b-svc'; insert into $SCHEMA.consumer_replay " +
                    "(replay_id, consumer, producer, mode, scope, topics, requested_at) values " +
                    "('${UUID.randomUUID()}', '$CONSUMER', 'b-svc', 'SCOPE', '$scopeJson', array['$MULTI'], now())",
            )
            assertEquals(refused(RebuildErrorCode.OTHER_REPLAY_PENDING), h.runtime().rebuildConsumer(h.request(topic = MULTI)))
            assertEquals(3L, h.count("select count(*) from $SCHEMA.outbox"))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-Q-3845 a set's replay stays pending until its incident closes, not until each pair completes`(image: String) {
        Harness(image).use { h ->
            h.behind(watermark = 5, topic = MULTI)
            h.behind(watermark = 5, topic = A_ONLY)
            h.matchView()
            h.fixture.runAsAdmin("insert into $SCHEMA.match_view values ('$CONSUMER', '${TENANT.value}', 'n')")
            val clears = listOf("a-svc", "b-svc").map { ReadModelClear(it, listOf(CLEAR)) }
            val runtime = h.runtime(fullSync = true, clears = clears, extra = listOf(A_ONLY))
            val done = runtime.rebuildConsumer(h.request(topic = MULTI)) as RebuildOutcome.Done
            assertEquals(listOf("a-svc", "b-svc"), done.state.producers)
            val written = h.count("select count(*) from $SCHEMA.outbox")
            fun nothingWritten(step: String) {
                assertEquals(written, h.count("select count(*) from $SCHEMA.outbox"), step)
                assertEquals(2L, h.count("select count(*) from $SCHEMA.consumer_replay"), step)
                assertNull(h.nextOffsetOrNull(A_ONLY), "$step: no head move on a-svc's own topic")
                assertEquals(6L, h.nextOffset(MULTI), "$step: no second head move")
                assertEquals(1L, h.count("select count(*) from $SCHEMA.match_view"), "$step: nothing cleared")
            }

            // a-svc completed, b-svc still pending: a replay on a-svc's own topic touches the open set's pair, EVL-004.
            h.fixture.runAsAdmin("update $SCHEMA.consumer_replay set completed_at = now() where producer = 'a-svc'")
            assertEquals(refused(RebuildErrorCode.OTHER_REPLAY_PENDING), runtime.rebuildConsumer(h.request(topic = A_ONLY)))
            nothingWritten("a-svc completed, scope replay on its own topic")
            // A full sync on the part-complete set is refused too.
            val full = h.request(topic = MULTI, mode = ReplayMode.FULL_SYNC, scope = null)
            assertEquals(refused(RebuildErrorCode.OTHER_REPLAY_PENDING), runtime.rebuildConsumer(full))
            nothingWritten("a-svc completed, full sync")

            // Every pair completed but the incident not yet closed (closeCompleted not yet run, or the sink failed):
            // a same mode and scope call returns the pending set, never EVL-003; a-svc's own topic is still EVL-004.
            h.fixture.runAsAdmin("update $SCHEMA.consumer_replay set completed_at = now() where producer = 'b-svc'")
            val repeat = runtime.rebuildConsumer(h.request(topic = MULTI)) as RebuildOutcome.Done
            assertEquals(listOf(false, true), listOf(repeat.created, repeat.state.repeat))
            assertEquals(done.state.replayId, repeat.state.replayId)
            assertEquals(done.state.producers, repeat.state.producers)
            assertEquals(refused(RebuildErrorCode.OTHER_REPLAY_PENDING), runtime.rebuildConsumer(h.request(topic = A_ONLY)))
            nothingWritten("every pair completed, incident open")

            // Once the incident has closed: EVL-003 on the rebuilt topic, and a-svc's pair is free for a new replay.
            h.fixture.runAsAdmin("update $SCHEMA.consumer_replay set closed_at = now()")
            assertEquals(refused(RebuildErrorCode.NOT_REBUILD_REQUIRED), runtime.rebuildConsumer(h.request(topic = MULTI)))
            val next = runtime.rebuildConsumer(h.request(topic = A_ONLY)) as RebuildOutcome.Done
            assertTrue(next.created)
            assertEquals(listOf("a-svc"), next.state.producers)
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-Q-3788 a full sync addresses every producer of the topics it moves and deletes their docsequence rows`(image: String) {
        Harness(image).use { h ->
            h.behind(watermark = 5)
            h.matchView()
            h.fixture.runAsAdmin(
                "insert into $SCHEMA.match_view values ('$CONSUMER', '${TENANT.value}', 'n');" +
                    "insert into $SCHEMA.consumer_sequence values ('$CONSUMER', '/gst/$PRODUCER', 's', '${TENANT.value}', 4);" +
                    "insert into $SCHEMA.consumer_sequence values ('$CONSUMER', '/gst/b-svc', 's', '${TENANT.value}', 4);" +
                    "insert into $SCHEMA.consumer_sequence values ('$CONSUMER', '/gst/other-svc', 's', '${TENANT.value}', 4)",
            )
            val full = h.request(mode = ReplayMode.FULL_SYNC, scope = null)
            // canonical-svc shares SHARED with b-svc, and b-svc shares MULTI with a-svc: all three are addressed.
            val partial = listOf(ReadModelClear(PRODUCER, listOf(CLEAR)), ReadModelClear("b-svc", listOf(CLEAR)))
            assertThrows(IllegalStateException::class.java) {
                h.runtime(fullSync = true, clears = partial, extra = listOf(SHARED)).rebuildConsumer(full)
            }
            assertEquals(3L, h.count("select count(*) from $SCHEMA.consumer_sequence"), "no clear for a-svc: nothing deleted")
            assertEquals(0L, h.count("select count(*) from $SCHEMA.outbox"), "no clear for a-svc: refused, nothing written")
            val clears = listOf(PRODUCER, "a-svc", "b-svc").map { ReadModelClear(it, listOf(CLEAR)) }
            val done = h.runtime(fullSync = true, clears = clears, extra = listOf(SHARED)).rebuildConsumer(full) as RebuildOutcome.Done
            assertEquals(listOf("a-svc", "b-svc", PRODUCER), done.state.producers)
            assertEquals(ReplayState.SEVERAL_PRODUCERS, done.state.producer)
            assertEquals(listOf(MULTI, SHARED, LIVE).sorted(), h.strings("select topic from $SCHEMA.consumer_offset order by topic"))
            val requested = h.data(ReplayProtocol.REPLAY_REQUESTED)
            assertEquals(listOf("a-svc", "b-svc", PRODUCER), requested.map { it.get("producer").asString() }.sorted())
            assertEquals(setOf(done.state.replayId.toString()), requested.map { it.get("replayId").asString() }.toSet())
            assertEquals(0L, h.count("select count(*) from $SCHEMA.match_view"), "every addressee's clear ran")
            assertEquals(
                listOf("/gst/other-svc"),
                h.strings("select source from $SCHEMA.consumer_sequence"),
                "every addressee's docsequence rows deleted, another producer's kept (INV-Q-3843)",
            )
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a full sync without the service's read-model clear for the producer is refused`(image: String) {
        Harness(image).use { h ->
            h.behind(watermark = 5)
            h.fixture.runAsAdmin(
                "insert into $SCHEMA.consumer_sequence values ('$CONSUMER', '/gst/$PRODUCER', 's', '${TENANT.value}', 4)",
            )
            val full = h.request(mode = ReplayMode.FULL_SYNC, scope = null)
            val runtime = h.runtime(fullSync = true, clears = emptyList())
            assertThrows(IllegalStateException::class.java) { runtime.rebuildConsumer(full) }
            assertEquals(1L, h.count("select count(*) from $SCHEMA.consumer_sequence"))
            assertNull(h.nextOffsetOrNull())
            assertEquals(0L, h.count("select count(*) from $SCHEMA.outbox"))
        }
    }

    private fun refused(code: RebuildErrorCode) = RebuildOutcome.Refused(code)

    private inner class Harness(image: String) : AutoCloseable {
        val fixture = OutboxPostgresFixture(image)
        private val tenants = TenantContextImpl()
        private val tx = TransactionRunnerImpl(fixture.appDataSource(), tenants)
        private val outbox = JdbcOutboxWriter(tx, SCHEMA, CONSUMER, "$CONSUMER-1", CATALOGUE, Clock.systemUTC())

        init {
            fixture.createService(SCHEMA)
            fixture.createTopics(CATALOGUE)
        }

        private val syncTenants = TenantContextImpl()
        private val syncTx = TransactionRunnerImpl(fixture.runtimeDataSource(), syncTenants)
        private val syncOutbox = JdbcOutboxWriter(syncTx, SCHEMA, CONSUMER, "$CONSUMER-w", CATALOGUE, Clock.systemUTC())

        fun runtime(
            audit: AuditWriter = OutboxAuditWriter(outbox),
            fullSync: Boolean = false,
            syncAudit: AuditWriter = OutboxAuditWriter(syncOutbox),
            clears: List<ReadModelClear> = listOf(ReadModelClear(PRODUCER, listOf(CLEAR))),
            extra: List<String> = emptyList(),
        ) = ConsumerRebuildRuntime(
            CONSUMER, SCHEMA, CATALOGUE,
            (listOf(LIVE, MULTI) + extra).map { ConsumerSubscription(CONSUMER, it, SCHEMA) },
            TenantScope(tenants, tx, tx, OutboxPostgresFixture.WORKLOAD), outbox, audit,
            PostgresEventLogReader(fixture.eventLogDataSource(), fixture.eventLogScope(TenantContextImpl())),
            { it == "platform.engineer" }, Clock.systemUTC(),
            if (fullSync) {
                FullSyncWiring(
                    TenantScope(syncTenants, syncTx, syncTx, OutboxPostgresFixture.WORKLOAD),
                    syncOutbox,
                    syncAudit,
                    clears,
                )
            } else {
                null
            },
        )

        /** A tenant read-model table cleared by the full sync, through its sys-owned clear function (INV-Q-4064). */
        fun matchView() = fixture.run(
            """
            create table $SCHEMA.match_view (consumer text not null, tenant_id uuid not null, note text not null);
            ${TenantRlsMigration.ddl(SCHEMA, "match_view", policyName = "match_view_tenant_isolation")}
            ${OutboxSchema.readModelClearFunction(SCHEMA, "match_view", OutboxPostgresFixture.APP, OutboxPostgresFixture.SYS, "consumer")}
            """.trimIndent(),
        )

        fun strings(sql: String): List<String> = fixture.adminDataSource().connection.use { connection ->
            connection.createStatement().executeQuery(sql).use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }

        fun request(
            actor: String = "platform.engineer",
            topic: String = LIVE,
            mode: ReplayMode = ReplayMode.SCOPE,
            scope: ReplayScope? = ReplayScope.Tenant(TENANT),
        ) = RebuildConsumerRequest(actor, topic, mode, scope, UUID.randomUUID(), null, CorrelationId("corr-1"), CausationId("op-1"))

        fun behind(
            watermark: Long,
            topic: String = LIVE,
        ) = fixture.runAsAdmin(
            "insert into eventlog.purge_watermark values ('${EventLogSchema.tableName(topic)}', $watermark, now())",
        )

        fun nextOffsetOrNull(topic: String = LIVE): Long? = fixture.adminDataSource().connection.use { connection ->
            connection.prepareStatement("select next_offset from $SCHEMA.consumer_offset where consumer = ? and topic = ?").use {
                it.setString(1, CONSUMER)
                it.setString(2, topic)
                it.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
            }
        }

        fun nextOffset(topic: String = LIVE): Long = checkNotNull(nextOffsetOrNull(topic))

        fun count(sql: String): Long = fixture.adminDataSource().connection.use { connection ->
            connection.createStatement().executeQuery(sql).use { rows ->
                rows.next()
                rows.getLong(1)
            }
        }

        fun data(type: String) = fixture.adminDataSource().connection.use { connection ->
            connection.prepareStatement("select data::text from $SCHEMA.outbox where type = ? order by seq").use {
                it.setString(1, type)
                it.executeQuery().use { rows -> buildList { while (rows.next()) add(mapper.readTree(rows.getString(1))) } }
            }
        }

        override fun close() = fixture.close()
    }

    private companion object {
        const val SCHEMA = "reconciliation"
        const val CONSUMER = "reconciliation-svc"
        const val PRODUCER = "canonical-svc"
        const val LIVE = "gst.canonical.canonical-version-created.v1"
        const val MULTI = "gst.platform.connection-status-changed.v1"
        const val SHARED = "gst.platform.evidence-artefact-recorded.v1"
        const val A_ONLY = "gst.a.thing-recorded.v1"
        val CLEAR = ReadModelTable("match_view", CONSUMER)
        val TENANT = TenantId(UUID.fromString("0a000000-0000-0000-0000-00000000000a"))
        val CATALOGUE =
            EventCatalogue(
                listOf(
                    CatalogueEntry("com.dynokore.gst.canonical.CanonicalVersionCreated.v1", LIVE, setOf(PRODUCER), false),
                    CatalogueEntry("com.dynokore.gst.platform.ConnectionStatusChanged.v1", MULTI, setOf("a-svc", "b-svc"), false),
                    CatalogueEntry("com.dynokore.gst.platform.EvidenceArtefactRecorded.v1", SHARED, setOf(PRODUCER, "b-svc"), false),
                    CatalogueEntry("com.dynokore.gst.a.ThingRecorded.v1", A_ONLY, setOf("a-svc"), false),
                    CatalogueEntry(OutboxAuditWriter.EVENT_TYPE, "gst.platform.audit-entry-recorded.v1", setOf(CatalogueEntry.EVERY_SERVICE), false),
                ),
                listOf(
                    ReplayTopic(
                        "gst.canonical.replay-reconciliation.v1",
                        setOf("com.dynokore.gst.canonical.CanonicalVersionCreated.v1", ReplayProtocol.REPLAY_COMPLETED),
                        PRODUCER,
                        CONSUMER,
                        false,
                    ),
                ),
                listOf(ReplayRequestTopic("gst.reconciliation.replay-requested.v1", CONSUMER, setOf(PRODUCER))),
            )
    }
}
