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
import com.dynokore.platform.outboxandqueue.ReadModelClear
import com.dynokore.platform.outboxandqueue.ReadModelTable
import com.dynokore.platform.outboxandqueue.ReplayJson
import com.dynokore.platform.outboxandqueue.ReplayMode
import com.dynokore.platform.outboxandqueue.ReplayProtocol
import com.dynokore.platform.outboxandqueue.ReplayRequestTopic
import com.dynokore.platform.outboxandqueue.ReplayScope
import com.dynokore.platform.outboxandqueue.ReplayTopic
import com.dynokore.platform.outboxandqueue.TenantScope
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
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.OffsetDateTime
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
            assertEquals(
                refused(RebuildErrorCode.TOPIC_NOT_CONSUMED),
                h.runtime().rebuildConsumer(h.request(topic = "gst.other.thing.v1")),
            )
            assertEquals(
                refused(RebuildErrorCode.ROLE_NOT_PERMITTED),
                h.runtime().rebuildConsumer(h.request(actor = "tenant.admin")),
            )
            assertEquals(
                refused(RebuildErrorCode.NOT_REBUILD_REQUIRED),
                h.runtime().rebuildConsumer(h.request()),
                "no watermark yet",
            )
            assertEquals(0L, h.count("outbox"))
            assertEquals(0L, h.count("consumer_replay"))
        }
    }

    /**
     * Replay state (INV-T-4134; INV-Q-3671, INV-Q-4115): the stored `consumer_replay.scope` of each scope kind is the
     * JSON of the request's scope; a caller who is not a platform operator gets EVL-002 and nothing is written, with the
     * request otherwise valid (a purge watermark ahead of the consumer).
     */
    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `the stored replay scope of each kind equals the request's scope JSON`(image: String) {
        val other = TenantId(UUID.fromString("0b000000-0000-0000-0000-00000000000b"))
        val scopes =
            listOf(
                ReplayScope.Tenant(TENANT),
                ReplayScope.Entity(
                    other,
                    com.dynokore.platform.outboxandqueue
                        .EntityRef("entity-7"),
                ),
                ReplayScope.Period(
                    java.time.Instant.parse("2026-09-01T00:00:00Z"),
                    java.time.Instant.parse("2026-10-01T00:00:00Z"),
                    TENANT,
                ),
                ReplayScope.Period(
                    java.time.Instant.parse("2026-09-01T00:00:00Z"),
                    java.time.Instant.parse("2026-10-01T00:00:00Z"),
                ),
            )
        scopes.forEach { scope ->
            Harness(image).use { h ->
                h.behind(watermark = 5)
                val done = h.runtime().rebuildConsumer(h.request(scope = scope)) as RebuildOutcome.Done
                assertTrue(done.created)
                val stored =
                    h.strings("consumer_replay", "scope", where = f("replay_id").eq(done.state.replayId))
                assertEquals(1, stored.size)
                assertEquals(
                    mapper.readTree(ReplayJson().writeScope(scope)),
                    mapper.readTree(stored.single()),
                    "$scope",
                )
            }
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a caller who is not a platform operator gets EVL-002 and no replay row, request or audit entry is written`(
        image: String,
    ) {
        Harness(image).use { h ->
            h.behind(watermark = 5)
            assertEquals(
                refused(RebuildErrorCode.ROLE_NOT_PERMITTED),
                h.runtime().rebuildConsumer(h.request(actor = "tenant.admin")),
            )
            assertEquals(0L, h.count("consumer_replay"))
            assertEquals(0L, h.count("outbox", f("type").like("%ReplayRequested%")))
            assertEquals(0L, h.count("outbox", f("type").like("%AuditEntryRecorded%")))
            assertNull(h.nextOffsetOrNull(), "the head is not moved")
            // the same request by a platform operator is accepted, so the refusal was the role
            assertNotNull(h.runtime().rebuildConsumer(h.request()) as? RebuildOutcome.Done)
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `INV-AC-3657 the head move, the request and the audit entry commit together, a repeat writes nothing`(
        image: String,
    ) {
        Harness(image).use { h ->
            h.behind(watermark = 5)
            h.captureActors("$SCHEMA.outbox")
            val request = h.request()
            val done = h.runtime().rebuildConsumer(request) as RebuildOutcome.Done
            assertTrue(done.created)
            assertFalse(done.state.repeat)
            assertEquals(
                listOf(LIVE, PRODUCER, ReplayMode.SCOPE),
                listOf(done.state.topic, done.state.producer, done.state.mode),
            )
            assertEquals(listOf(PRODUCER), done.state.producers)
            assertEquals(6L, h.nextOffset(), "moved past max(head, watermark)")
            assertEquals(
                setOf("platform.engineer"),
                h.capturedActors("$SCHEMA.outbox").toSet(),
                "the operator's transaction is bound to the operator, never the service (INV-Q-4268)",
            )

            Checks(h).assertRequestedAndAudited(done, request)

            Checks(h).assertRepeatWritesNothing(done)
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
            assertEquals(0L, h.count("outbox"))
            assertEquals(0L, h.count("consumer_replay"))
            assertNotNull(h.runtime().rebuildConsumer(h.request()) as? RebuildOutcome.Done, "a later call succeeds")
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-Q-3786 a full sync clears both tenants' rows of the producer on the runtime login in one transaction`(
        image: String,
    ) {
        Harness(image).use { h ->
            h.behind(watermark = 5)
            h.matchView()
            val other = TenantId(UUID.randomUUID())
            Checks(h).seedRows(listOf(TENANT, other))
            val full = h.request(mode = ReplayMode.FULL_SYNC, scope = null)
            val done = h.runtime(fullSync = true).rebuildConsumer(full) as RebuildOutcome.Done
            assertTrue(done.created)
            assertEquals(ReplayMode.FULL_SYNC, done.state.mode)
            assertEquals(
                0L,
                h.count("match_view"),
                "the read model of both tenants cleared",
            )
            assertEquals(
                listOf("/gst/other-svc", "/gst/other-svc"),
                h.strings("consumer_sequence", "source", orderBy = "source"),
                "the producer's docsequence rows of both tenants deleted, another producer's kept",
            )
            assertEquals(2L, h.count("inbox"), "the inbox kept")
            assertEquals(6L, h.nextOffset())
            assertEquals(
                "FULL_SYNC",
                h
                    .data(ReplayProtocol.REPLAY_REQUESTED)
                    .single()
                    .get("mode")
                    .asString(),
            )
            assertEquals(1, h.data(OutboxAuditWriter.EVENT_TYPE).size)
            assertEquals(
                listOf(TenantId.PLATFORM.value.toString()),
                h.strings("outbox", "tenant_id", distinct = true),
            )
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a failing audit write in a full sync leaves the rows and the position as they were`(image: String) {
        Harness(image).use { h ->
            h.behind(watermark = 5)
            h.matchView()
            h.insert("match_view", CONSUMER, TENANT.value, "n")
            h.insert("consumer_sequence", CONSUMER, "/gst/$PRODUCER", "s", TENANT.value, 4)
            val failing =
                object : AuditWriter {
                    override fun record(
                        entry: AuditEntry,
                        context: EventContext,
                    ): Envelope = error("audit store down")
                }
            val full = h.request(mode = ReplayMode.FULL_SYNC, scope = null)
            assertThrows(
                IllegalStateException::class.java,
            ) { h.runtime(fullSync = true, audit = failing).rebuildConsumer(full) }
            assertEquals(1L, h.count("consumer_sequence"))
            assertEquals(1L, h.count("match_view"))
            assertNull(h.nextOffsetOrNull())
            assertEquals(0L, h.count("outbox"))
            assertEquals(0L, h.count("consumer_replay"))
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
            assertEquals(
                listOf(MULTI),
                h.strings("consumer_offset", "topic"),
                "one head move, this topic only",
            )
            assertEquals(6L, h.nextOffset(MULTI))
            val requested = h.data(ReplayProtocol.REPLAY_REQUESTED)
            assertEquals(
                listOf("a-svc", "b-svc"),
                requested.map { it.get("producer").asString() }.sorted(),
                "one per producer",
            )
            assertEquals(setOf(done.state.replayId.toString()), requested.map { it.get("replayId").asString() }.toSet())
            Checks(h).assertAuditNamesEveryAddressee()
            assertEquals(3L, h.count("outbox"))

            Checks(h).repeatOverPairs(done)
            // Another replay (same mode and scope) pending on one of the pairs only is EVL-004 too.
            val scopeJson = ReplayJson().writeScope(ReplayScope.Tenant(TENANT))
            h.delete("consumer_replay", f("producer").eq("b-svc"))
            h.insert(
                "consumer_replay",
                UUID.randomUUID(),
                CONSUMER,
                "b-svc",
                "SCOPE",
                scopeJson,
                arrayOf(MULTI),
                OffsetDateTime.now(),
                null,
                null,
            )
            assertEquals(
                refused(RebuildErrorCode.OTHER_REPLAY_PENDING),
                h.runtime().rebuildConsumer(h.request(topic = MULTI)),
            )
            assertEquals(3L, h.count("outbox"))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-Q-3845 a set's replay stays pending until its incident closes, not until each pair completes`(
        image: String,
    ) {
        Harness(image).use { h ->
            h.behind(watermark = 5, topic = MULTI)
            h.behind(watermark = 5, topic = A_ONLY)
            h.matchView()
            h.insert("match_view", CONSUMER, TENANT.value, "n")
            val clears = listOf("a-svc", "b-svc").map { ReadModelClear(it, listOf(CLEAR)) }
            val runtime = h.runtime(fullSync = true, clears = clears, extra = listOf(A_ONLY))
            val done = runtime.rebuildConsumer(h.request(topic = MULTI)) as RebuildOutcome.Done
            assertEquals(listOf("a-svc", "b-svc"), done.state.producers)
            val written = h.count("outbox")

            fun nothingWritten(step: String) {
                assertEquals(written, h.count("outbox"), step)
                assertEquals(2L, h.count("consumer_replay"), step)
                assertNull(h.nextOffsetOrNull(A_ONLY), "$step: no head move on a-svc's own topic")
                assertEquals(6L, h.nextOffset(MULTI), "$step: no second head move")
                assertEquals(1L, h.count("match_view"), "$step: nothing cleared")
            }

            // a-svc completed, b-svc still pending: a replay on a-svc's own topic touches the open set's pair, EVL-004.
            h.update("consumer_replay", "completed_at", f("producer").eq("a-svc"))
            assertEquals(
                refused(RebuildErrorCode.OTHER_REPLAY_PENDING),
                runtime.rebuildConsumer(h.request(topic = A_ONLY)),
            )
            nothingWritten("a-svc completed, scope replay on its own topic")
            // A full sync on the part-complete set is refused too.
            val full = h.request(topic = MULTI, mode = ReplayMode.FULL_SYNC, scope = null)
            assertEquals(refused(RebuildErrorCode.OTHER_REPLAY_PENDING), runtime.rebuildConsumer(full))
            nothingWritten("a-svc completed, full sync")

            // Every pair completed but the incident not yet closed (closeCompleted not yet run, or the sink failed):
            // a same mode and scope call returns the pending set, never EVL-003; a-svc's own topic is still EVL-004.
            h.update("consumer_replay", "completed_at", f("producer").eq("b-svc"))
            val repeat = runtime.rebuildConsumer(h.request(topic = MULTI)) as RebuildOutcome.Done
            assertEquals(listOf(false, true), listOf(repeat.created, repeat.state.repeat))
            assertEquals(done.state.replayId, repeat.state.replayId)
            assertEquals(done.state.producers, repeat.state.producers)
            assertEquals(
                refused(RebuildErrorCode.OTHER_REPLAY_PENDING),
                runtime.rebuildConsumer(h.request(topic = A_ONLY)),
            )
            nothingWritten("every pair completed, incident open")

            // Once the incident has closed: EVL-003 on the rebuilt topic, and a-svc's pair is free for a new replay.
            h.update("consumer_replay", "closed_at")
            assertEquals(
                refused(RebuildErrorCode.NOT_REBUILD_REQUIRED),
                runtime.rebuildConsumer(h.request(topic = MULTI)),
            )
            val next = runtime.rebuildConsumer(h.request(topic = A_ONLY)) as RebuildOutcome.Done
            assertTrue(next.created)
            assertEquals(listOf("a-svc"), next.state.producers)
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-Q-3788 a full sync addresses every producer of the topics it moves and deletes their docsequence rows`(
        image: String,
    ) {
        Harness(image).use { h ->
            h.behind(watermark = 5)
            h.matchView()
            h.insert("match_view", CONSUMER, TENANT.value, "n")
            h.insert("consumer_sequence", CONSUMER, "/gst/$PRODUCER", "s", TENANT.value, 4)
            h.insert("consumer_sequence", CONSUMER, "/gst/b-svc", "s", TENANT.value, 4)
            h.insert("consumer_sequence", CONSUMER, "/gst/other-svc", "s", TENANT.value, 4)
            val full = h.request(mode = ReplayMode.FULL_SYNC, scope = null)
            // canonical-svc shares SHARED with b-svc, and b-svc shares MULTI with a-svc: all three are addressed.
            Checks(h).assertPartialClearsRefused(full)
            val clears = listOf(PRODUCER, "a-svc", "b-svc").map { ReadModelClear(it, listOf(CLEAR)) }
            val done =
                h
                    .runtime(
                        fullSync = true,
                        clears = clears,
                        extra = listOf(SHARED),
                    ).rebuildConsumer(full) as RebuildOutcome.Done
            assertEquals(listOf("a-svc", "b-svc", PRODUCER), done.state.producers)
            assertEquals(ReplayState.SEVERAL_PRODUCERS, done.state.producer)
            assertEquals(
                listOf(MULTI, SHARED, LIVE).sorted(),
                h.strings("consumer_offset", "topic", orderBy = "topic"),
            )
            val requested = h.data(ReplayProtocol.REPLAY_REQUESTED)
            assertEquals(listOf("a-svc", "b-svc", PRODUCER), requested.map { it.get("producer").asString() }.sorted())
            assertEquals(setOf(done.state.replayId.toString()), requested.map { it.get("replayId").asString() }.toSet())
            assertEquals(0L, h.count("match_view"), "every addressee's clear ran")
            assertEquals(
                listOf("/gst/other-svc"),
                h.strings("consumer_sequence", "source"),
                "every addressee's docsequence rows deleted, another producer's kept (INV-Q-3843)",
            )
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a full sync without the service's read-model clear for the producer is refused`(image: String) {
        Harness(image).use { h ->
            h.behind(watermark = 5)
            h.insert("consumer_sequence", CONSUMER, "/gst/$PRODUCER", "s", TENANT.value, 4)
            val full = h.request(mode = ReplayMode.FULL_SYNC, scope = null)
            val runtime = h.runtime(fullSync = true, clears = emptyList())
            assertThrows(IllegalStateException::class.java) { runtime.rebuildConsumer(full) }
            assertEquals(1L, h.count("consumer_sequence"))
            assertNull(h.nextOffsetOrNull())
            assertEquals(0L, h.count("outbox"))
        }
    }

    /** Assertions and seeding shared by the tests above, over one [Harness]. */
    private inner class Checks(
        private val h: Harness,
    ) {
        /** The request event and the audit entry of [done]: the operator, the action, the key and the prior position. */
        fun assertRequestedAndAudited(
            done: RebuildOutcome.Done,
            request: RebuildConsumerRequest,
        ) {
            val requested = h.data(ReplayProtocol.REPLAY_REQUESTED).single()
            assertEquals(done.state.replayId.toString(), requested.get("replayId").asString())
            assertEquals(PRODUCER, requested.get("producer").asString())
            val audit = h.data(OutboxAuditWriter.EVENT_TYPE).single()
            assertEquals("platform.engineer", audit.get("actor").asString())
            assertEquals("rebuildConsumer", audit.get("action").asString())
            assertEquals("Idempotency-Key ${request.idempotencyKey}", audit.get("reason").asString())
            assertEquals(
                1L,
                audit
                    .get("priorState")
                    .get("positions")
                    .get(0)
                    .get("nextOffset")
                    .asLong(),
            )
            assertEquals(LIVE, mapper.readTree(audit.get("scope").asString()).get("topic").asString())
        }

        /** A repeat returns the pending replay [done] and writes nothing; another scope while it is pending is EVL-004. */
        fun assertRepeatWritesNothing(done: RebuildOutcome.Done) {
            // A repeat for the pair with the same mode and scope: the pending replay, nothing written (INV-Q-3665).
            val repeat = h.runtime().rebuildConsumer(h.request()) as RebuildOutcome.Done
            assertFalse(repeat.created)
            assertTrue(repeat.state.repeat)
            assertEquals(done.state.replayId, repeat.state.replayId)
            assertEquals(2L, h.count("outbox"))
            // ...another scope while it is pending: EVL-004.
            val other = h.request(scope = ReplayScope.Tenant(TenantId(UUID.randomUUID())))
            assertEquals(refused(RebuildErrorCode.OTHER_REPLAY_PENDING), h.runtime().rebuildConsumer(other))
            assertEquals(2L, h.count("outbox"))
        }

        /** Per tenant: a read-model row, docsequence rows of the producer and another producer, and an inbox row. */
        fun seedRows(tenants: List<TenantId>) {
            for (tenant in tenants) {
                h.insert("match_view", CONSUMER, tenant.value, "n")
                h.insert("consumer_sequence", CONSUMER, "/gst/$PRODUCER", "s", tenant.value, 4)
                h.insert("consumer_sequence", CONSUMER, "/gst/other-svc", "s", tenant.value, 4)
                h.insertColumns(
                    "inbox",
                    "consumer" to CONSUMER,
                    "event_id" to "e-${tenant.value}",
                    "tenant_id" to tenant.value,
                )
            }
        }

        /** The single audit entry's scope names a-svc and b-svc. */
        fun assertAuditNamesEveryAddressee() {
            val scope =
                mapper.readTree(
                    h
                        .data(OutboxAuditWriter.EVENT_TYPE)
                        .single()
                        .get("scope")
                        .asString(),
                )
            assertEquals(
                listOf("a-svc", "b-svc"),
                scope.get("producers").let { a ->
                    (0 until a.size()).map { a.get(it).asString() }
                },
                "the audit names every addressee",
            )
        }

        /** Without a clear for a-svc the full sync is refused: nothing deleted, nothing written. */
        fun assertPartialClearsRefused(full: RebuildConsumerRequest) {
            val partial = listOf(ReadModelClear(PRODUCER, listOf(CLEAR)), ReadModelClear("b-svc", listOf(CLEAR)))
            assertThrows(IllegalStateException::class.java) {
                h.runtime(fullSync = true, clears = partial, extra = listOf(SHARED)).rebuildConsumer(full)
            }
            assertEquals(
                3L,
                h.count("consumer_sequence"),
                "no clear for a-svc: nothing deleted",
            )
            assertEquals(
                0L,
                h.count("outbox"),
                "no clear for a-svc: refused, nothing written",
            )
        }

        /** The repeat rule over a set of pairs, before and after one pair completed; another scope is EVL-004. */
        fun repeatOverPairs(done: RebuildOutcome.Done) {
            // The repeat rule over the set of pairs: the pending replay, nothing written; also once one pair completed,
            // since the replay stays pending until its incident closes (coordinator ruling INV-Q-3845).
            for (step in listOf("both pending", "a-svc completed")) {
                val repeat = h.runtime().rebuildConsumer(h.request(topic = MULTI)) as RebuildOutcome.Done
                assertEquals(listOf(false, true), listOf(repeat.created, repeat.state.repeat), step)
                assertEquals(done.state.replayId, repeat.state.replayId, step)
                assertEquals(done.state.producers, repeat.state.producers, step)
                h.update("consumer_replay", "completed_at", f("producer").eq("a-svc"))
            }
            val other = h.request(topic = MULTI, scope = ReplayScope.Tenant(TenantId(UUID.randomUUID())))
            assertEquals(refused(RebuildErrorCode.OTHER_REPLAY_PENDING), h.runtime().rebuildConsumer(other))
        }
    }

    private fun refused(code: RebuildErrorCode) = RebuildOutcome.Refused(code)

    private inner class Harness(
        image: String,
    ) : AutoCloseable {
        val fixture =
            OutboxPostgresFixture(
                image,
                OutboxPostgresFixture.serviceInitSql(SCHEMA, MATCH_VIEW_DDL) +
                    OutboxPostgresFixture.eventLogInitSql(CATALOGUE) +
                    OutboxPostgresFixture.captureActorsInitSql(listOf("$SCHEMA.outbox")),
            )
        private val db =
            TestDatabase(
                fixture.jdbcUrl(),
                (
                    listOf(
                        "outbox",
                        "consumer_replay",
                        "consumer_offset",
                        "consumer_sequence",
                        "inbox",
                        "match_view",
                    ).map { "$SCHEMA.$it" } + listOf("eventlog.purge_watermark", "public.captured_actor")
                ).associateWith { TableClass.PLATFORM_SCOPED },
            )

        /** The superuser's access (no tenant predicates), for the state the tests seed and read. */
        private val admin = db.login(fixture.adminUser(), fixture.adminPassword())
        private val app = db.login(OutboxPostgresFixture.APP, OutboxPostgresFixture.APP_PASSWORD)
        private val tenants = db.tenants
        private val tx = app.runner
        private val outbox =
            JdbcOutboxWriter(
                TenancyJooq.dslContext(OutboxSchema.jooqTables(SCHEMA), tenants, tx),
                SCHEMA,
                CONSUMER,
                "$CONSUMER-1",
                CATALOGUE,
                Clock.systemUTC(),
            )

        private fun tbl(name: String) =
            DSL.table(
                DSL.name(
                    name.split('.').let {
                        if (it.size ==
                            1
                        ) {
                            listOf(SCHEMA, it[0])
                        } else {
                            it
                        }
                    },
                ),
            )

        fun captureActors(table: String) {
            admin.asPlatform {
                deleteFrom(
                    DSL.table(DSL.name("public", "captured_actor")),
                ).where(f("tbl").eq(table)).execute()
            }
        }

        fun capturedActors(table: String): List<String?> =
            admin.asPlatform {
                select(DSL.field(DSL.name("actor"), SQLDataType.CLOB))
                    .from(DSL.table(DSL.name("public", "captured_actor")))
                    .where(f("tbl").eq(table))
                    .orderBy(f("seq"))
                    .fetch(DSL.field(DSL.name("actor"), SQLDataType.CLOB))
            }

        fun runtime(
            audit: AuditWriter = OutboxAuditWriter(outbox),
            fullSync: Boolean = false,
            clears: List<ReadModelClear> = listOf(ReadModelClear(PRODUCER, listOf(CLEAR))),
            extra: List<String> = emptyList(),
        ) = ConsumerRebuildRuntime(
            CONSUMER,
            SCHEMA,
            CATALOGUE,
            (listOf(LIVE, MULTI) + extra).map { ConsumerSubscription(CONSUMER, it, SCHEMA) },
            TenantScope(tenants, tx, tx, OutboxPostgresFixture.WORKLOAD),
            outbox,
            audit,
            fixture.eventLogReader(),
            { it == "platform.engineer" },
            Clock.systemUTC(),
            if (fullSync) {
                FullSyncWiring(clears)
            } else {
                null
            },
        )

        /** The read-model table of the full sync is provisioned with the container ([MATCH_VIEW_DDL]); kept for the tests' reading order. */
        fun matchView() = Unit

        /** Rows of [table] (in [SCHEMA] unless qualified) that meet every one of [where]. */
        fun count(
            table: String,
            vararg where: Condition,
        ): Long = admin.asPlatform { fetchCount(tbl(table), DSL.and(*where)) }.toLong()

        /** Column [column] of the rows of [table], as text, in [orderBy] order. */
        fun strings(
            table: String,
            column: String,
            orderBy: String? = null,
            distinct: Boolean = false,
            where: Condition = DSL.noCondition(),
        ): List<String> =
            admin.asPlatform {
                val source = if (distinct) selectDistinct(f(column)) else select(f(column))
                source
                    .from(tbl(table))
                    .where(where)
                    .let { q -> if (orderBy != null) q.orderBy(f(orderBy)) else q }
                    .fetch()
                    .map { it.get(0).toString() }
            }

        /** A row with every column of [table], in table order. */
        fun insert(
            table: String,
            vararg values: Any?,
        ) {
            admin.asPlatform { insertInto(tbl(table)).values(*values).execute() }
        }

        /** A row with the named columns only. */
        fun insertColumns(
            table: String,
            vararg values: Pair<String, Any?>,
        ) {
            admin.asPlatform {
                insertInto(
                    tbl(table),
                    *values
                        .map {
                            f(it.first)
                        }.toTypedArray(),
                ).values(*values.map { it.second }.toTypedArray()).execute()
            }
        }

        fun delete(
            table: String,
            where: Condition,
        ) {
            admin.asPlatform { deleteFrom(tbl(table)).where(where).execute() }
        }

        /** Sets [column] to the database's now() on the rows of [table] that meet [where]. */
        fun update(
            table: String,
            column: String,
            where: Condition = DSL.noCondition(),
        ) {
            admin.asPlatform { update(tbl(table)).set(f(column), DSL.currentOffsetDateTime()).where(where).execute() }
        }

        fun request(
            actor: String = "platform.engineer",
            topic: String = LIVE,
            mode: ReplayMode = ReplayMode.SCOPE,
            scope: ReplayScope? = ReplayScope.Tenant(TENANT),
        ) = RebuildConsumerRequest(
            actor,
            topic,
            mode,
            scope,
            UUID.randomUUID(),
            null,
            CorrelationId("corr-1"),
            CausationId("op-1"),
        )

        fun behind(
            watermark: Long,
            topic: String = LIVE,
        ) = insert("eventlog.purge_watermark", EventLogSchema.tableName(topic), watermark, OffsetDateTime.now())

        fun nextOffsetOrNull(topic: String = LIVE): Long? =
            admin.asPlatform {
                select(f("next_offset"))
                    .from(tbl("consumer_offset"))
                    .where(f("consumer").eq(CONSUMER).and(f("topic").eq(topic)))
                    .fetchOne(0, Long::class.java)
            }

        fun nextOffset(topic: String = LIVE): Long = checkNotNull(nextOffsetOrNull(topic))

        fun data(type: String) =
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

        /** A tenant read-model table cleared by the full sync, through its sys-owned clear function (INV-Q-4064). */
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
                    CatalogueEntry(
                        "com.dynokore.gst.canonical.CanonicalVersionCreated.v1",
                        LIVE,
                        setOf(PRODUCER),
                        false,
                        retainedWhileResolvable = false,
                    ),
                    CatalogueEntry(
                        "com.dynokore.gst.platform.ConnectionStatusChanged.v1",
                        MULTI,
                        setOf("a-svc", "b-svc"),
                        false,
                        retainedWhileResolvable = false,
                    ),
                    CatalogueEntry(
                        "com.dynokore.gst.platform.EvidenceArtefactRecorded.v1",
                        SHARED,
                        setOf(PRODUCER, "b-svc"),
                        false,
                        retainedWhileResolvable = false,
                    ),
                    CatalogueEntry(
                        "com.dynokore.gst.a.ThingRecorded.v1",
                        A_ONLY,
                        setOf("a-svc"),
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
                        "gst.canonical.replay-reconciliation.v1",
                        setOf("com.dynokore.gst.canonical.CanonicalVersionCreated.v1", ReplayProtocol.REPLAY_COMPLETED),
                        PRODUCER,
                        CONSUMER,
                        false,
                        retainedWhileResolvable = false,
                    ),
                ),
                listOf(ReplayRequestTopic("gst.reconciliation.replay-requested.v1", CONSUMER, setOf(PRODUCER))),
            )
    }
}
