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
import com.dynokore.platform.outboxandqueue.JdbcOutboxWriter
import com.dynokore.platform.outboxandqueue.OutboxPostgresFixture
import com.dynokore.platform.outboxandqueue.OutboxSchema
import com.dynokore.platform.outboxandqueue.PostgresEventLogReader
import com.dynokore.platform.outboxandqueue.ReadModelClear
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
 * INV-Q-3667; INV-AC-3657) on PostgreSQL 17 and 18: the error codes in their order, the head move, ReplayRequested and
 * audit entry in one transaction, the repeat rule.
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
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-AC-3657 the head move, the request and the audit entry commit together, a repeat writes nothing`(image: String) {
        Harness(image).use { h ->
            h.behind(watermark = 5)
            val request = h.request()
            val done = h.runtime().rebuildConsumer(request) as RebuildOutcome.Done
            assertTrue(done.created)
            assertFalse(done.state.repeat)
            assertEquals(listOf(LIVE, PRODUCER, ReplayMode.SCOPE), listOf(done.state.topic, done.state.producer, done.state.mode))
            assertEquals(6L, h.nextOffset(), "moved past max(head, watermark)")

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
    fun `INV-Q-3786 a full sync clears both tenants' rows of the producer on the worker role in one transaction`(image: String) {
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
            assertThrows(IllegalStateException::class.java) { h.runtime(fullSync = true, workerAudit = failing).rebuildConsumer(full) }
            assertEquals(1L, h.count("select count(*) from $SCHEMA.consumer_sequence"))
            assertEquals(1L, h.count("select count(*) from $SCHEMA.match_view"))
            assertNull(h.nextOffsetOrNull())
            assertEquals(0L, h.count("select count(*) from $SCHEMA.outbox"))
            assertEquals(0L, h.count("select count(*) from $SCHEMA.consumer_replay"))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-Q-3788 a topic with several producers is refused until the owner rules`(image: String) {
        Harness(image).use { h ->
            h.fixture.runAsAdmin("insert into eventlog.purge_watermark values ('$MULTI', 5, now())")
            assertThrows(UnsupportedOperationException::class.java) { h.runtime().rebuildConsumer(h.request(topic = MULTI)) }
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

        private val workerTenants = TenantContextImpl()
        private val workerTx = TransactionRunnerImpl(fixture.workerDataSource(), workerTenants)
        private val workerOutbox = JdbcOutboxWriter(workerTx, SCHEMA, CONSUMER, "$CONSUMER-w", CATALOGUE, Clock.systemUTC())

        fun runtime(
            audit: AuditWriter = OutboxAuditWriter(outbox),
            fullSync: Boolean = false,
            workerAudit: AuditWriter = OutboxAuditWriter(workerOutbox),
        ) = ConsumerRebuildRuntime(
            CONSUMER, SCHEMA, CATALOGUE,
            listOf(ConsumerSubscription(CONSUMER, LIVE, SCHEMA), ConsumerSubscription(CONSUMER, MULTI, SCHEMA)),
            TenantScope(tenants, tx, tx), outbox, audit,
            PostgresEventLogReader(fixture.workerDataSource(), fixture.readerDataSource()),
            { it == "platform.engineer" }, Clock.systemUTC(),
            if (fullSync) {
                FullSyncWiring(
                    TenantScope(workerTenants, workerTx, workerTx),
                    workerOutbox,
                    workerAudit,
                    listOf(ReadModelClear(PRODUCER, listOf("delete from $SCHEMA.match_view where consumer = '$CONSUMER'"))),
                )
            } else {
                null
            },
        )

        /** A tenant read-model table cleared by the full sync, with the worker access of INV-Q-3786. */
        fun matchView() = fixture.run(
            """
            create table $SCHEMA.match_view (consumer text not null, tenant_id uuid not null, note text not null);
            ${TenantRlsMigration.ddl(SCHEMA, "match_view", policyName = "match_view_tenant_isolation")}
            ${OutboxSchema.readModelClearAccess(SCHEMA, "match_view", OutboxPostgresFixture.WORKER, listOf("consumer"))}
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

        fun behind(watermark: Long) = fixture.runAsAdmin("insert into eventlog.purge_watermark values ('$LIVE', $watermark, now())")

        fun nextOffsetOrNull(): Long? = fixture.adminDataSource().connection.use { connection ->
            connection.prepareStatement("select next_offset from $SCHEMA.consumer_offset where consumer = ? and topic = ?").use {
                it.setString(1, CONSUMER)
                it.setString(2, LIVE)
                it.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
            }
        }

        fun nextOffset(): Long = checkNotNull(nextOffsetOrNull())

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
        val TENANT = TenantId(UUID.fromString("0a000000-0000-0000-0000-00000000000a"))
        val CATALOGUE =
            EventCatalogue(
                listOf(
                    CatalogueEntry("com.dynokore.gst.canonical.CanonicalVersionCreated.v1", LIVE, setOf(PRODUCER), false),
                    CatalogueEntry("com.dynokore.gst.platform.ConnectionStatusChanged.v1", MULTI, setOf("a-svc", "b-svc"), false),
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
