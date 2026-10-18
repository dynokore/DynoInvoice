package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancyjooq.TenancyProbe
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.stream.Stream
import kotlin.concurrent.thread

/**
 * The check of outbox rows marked published whose event never reached the event log (owner rulings INV-Q-4334 (B),
 * INV-Q-4350 (A); the margin pending INV-Q-4403), on PostgreSQL 16, 17 and 18, with the event log on the service's
 * instance and on a second instance (INV-ADR-3968 item 8); and `eventlog.events_missing`'s rights (INV-REQ-1205 b3,
 * INV-AC-2175).
 */
@Tag("integration")
class UnrelayedEventCheckIT {
    private val catalogue = TestCatalogue.load()
    private val topic = "gst.canonical.canonical-version-created.v1"
    private val topicTable = "eventlog.gst_canonical_canonical_version_created_v1"
    private val margin = Duration.ofHours(1)

    /** The service schema on [service], the event log on [eventLog] (the same fixture, or a second instance). */
    private inner class Setup(
        val service: OutboxPostgresFixture,
        val eventLog: OutboxPostgresFixture,
    ) {
        val tenants = TenantContextImpl()
        val boundary = TransactionRunnerImpl(service.appDataSource(), tenants, null, SigningFixture.keys())
        val scope = TenantScope(tenants, boundary, boundary, OutboxPostgresFixture.WORKLOAD)
        val dsl = JooqKit.module(SCHEMA, tenants, boundary)
        val eventLogScope = eventLog.eventLogScope(TenantContextImpl())
        val eventLogDsl = JooqKit.eventLog(eventLogScope, catalogue)
        val writer =
            JdbcOutboxWriter(dsl, SCHEMA, "canonical-svc", "canonical-svc-1", catalogue, Clock.systemUTC())
        val relay =
            JdbcOutboxRelay(
                scope,
                dsl,
                SCHEMA,
                PostgresEventBinding(eventLogScope, eventLogDsl),
                Clock.systemUTC(),
                "background",
                2,
            )
        val raised = mutableListOf<UnrelayedEvent>()
        val check = newCheck()

        /** A check as another instance, or this one after a restart, would build it: no memory of earlier runs. */
        fun newCheck() =
            JdbcUnrelayedEventCheck(
                scope,
                dsl,
                SCHEMA,
                "canonical-svc",
                PostgresEventLogPresence(eventLogScope, eventLogDsl),
                UnrelayedEventSettings(Duration.ofDays(30), margin, batchSize = 2),
                UnrelayedEventSink { raised += it },
            )

        init {
            service.createService(SCHEMA)
            eventLog.createTopics(catalogue)
        }

        /** Writes one event in [TENANT]'s transaction; returns its outbox seq. */
        fun write(): Long =
            scope.bindWorkload(TenantId(TENANT)) {
                boundary.inTransaction {
                    append()
                    lastSeq()
                }
            }

        /** Appends one event in the current transaction. */
        fun append() {
            val doc = UUID.randomUUID()
            writer.append(
                listOf(OutboundEvent(TestCatalogue.CANONICAL_VERSION_CREATED, """{"version":1}""")),
                EventContext(
                    tenant = TenantId(TENANT),
                    entity = EntityRef("entity-1"),
                    docUuid = DocUuid(doc),
                    transactionDate = java.time.LocalDate.of(2026, 9, 30),
                    aggregateKey = doc.toString(),
                    aggregateVersion = null,
                    correlation = CorrelationId("corr"),
                    causation = CausationId("cmd"),
                ),
            )
        }

        /** As the runtime login, in the row's tenant transaction: marks an unrelayed row published (planted fault). */
        fun markPublished(seq: Long) = scope.bindWorkload(TenantId(TENANT)) { boundary.inTransaction { mark(seq) } }

        /** Marks [seq] published in the current transaction. */
        fun mark(seq: Long) {
            assertEquals(
                1,
                dsl
                    .update(OUTBOX)
                    .set(PUBLISHED_AT, OffsetDateTime.now())
                    .where(SEQ.eq(seq))
                    .execute(),
            )
        }

        /** The seq of the current transaction's last appended row. */
        fun lastSeq(): Long = checkNotNull(dsl.select(DSL.max(SEQ)).from(OUTBOX).fetchOne(0, Long::class.java))

        fun drain() {
            repeat(OutboxHarness.MAX_RUNS) { if (relay.runOnce().pending == 0L) return }
        }

        fun idOf(seq: Long): String =
            scope.bindWorkload(TenantId(TENANT)) {
                boundary.inTransaction {
                    checkNotNull(
                        dsl
                            .select(ID)
                            .from(OUTBOX)
                            .where(SEQ.eq(seq))
                            .fetchOne(ID),
                    )
                }
            }

        /** As the superuser with triggers off: moves a relayed row's times back. */
        fun age(
            seq: Long,
            insertedAgo: Duration,
            publishedAgo: Duration,
        ) {
            val id = idOf(seq)
            val log = DSL.name(*topicTable.split('.').toTypedArray())
            JooqKit.Access.admin(eventLog, JooqKit.platformScoped(topicTable)).single().asPlatform {
                it
                    .update(DSL.table(log))
                    .set(INSERTED_AT, OffsetDateTime.now().minus(insertedAgo))
                    .where(EVENT_ID.eq(id))
                    .execute()
            }
            // triggers off for this transaction only (a superuser setting, set through set_config)
            JooqKit.Access.admin(service, JooqKit.platformScoped("$SCHEMA.outbox")).single().asPlatform {
                it
                    .select(
                        DSL.function(
                            DSL.name("pg_catalog", "set_config"),
                            SQLDataType.CLOB,
                            DSL.inline("session_replication_role"),
                            DSL.inline("replica"),
                            DSL.inline(true),
                        ),
                    ).fetch()
                it
                    .update(OUTBOX)
                    .set(PUBLISHED_AT, OffsetDateTime.now().minus(publishedAgo))
                    .where(SEQ.eq(seq))
                    .execute()
            }
        }

        fun purgeEventLog() {
            JooqKit.Access.purge(eventLog, emptyMap()).single().asPlatform {
                it.select(DSL.function(DSL.name("eventlog", "purge_batch"), SQLDataType.OTHER)).fetch()
            }
        }

        fun close() {
            service.close()
            if (eventLog !== service) eventLog.close()
        }
    }

    private fun setup(
        image: String,
        secondInstance: Boolean,
    ): Setup {
        val service = OutboxPostgresFixture(image)
        return Setup(service, if (secondInstance) OutboxPostgresFixture(image) else service)
    }

    @ParameterizedTest(name = "{displayName}[{0}, event log on a second instance: {1}]")
    @MethodSource("topologies")
    fun `a planted unrelayed row marked published is reported once, and relayed and purged rows are not`(
        image: String,
        secondInstance: Boolean,
    ) {
        val s = setup(image, secondInstance)
        try {
            val relayed = s.write()
            val purged = s.write()
            val boundary = s.write()
            s.drain()
            assertTrue(
                s.check
                    .check()
                    .open
                    .isEmpty(),
                "relayed rows are never reported",
            )

            // Relayed long ago and purged by the event log: published 31 days ago, past the 30-day cut-off.
            s.age(purged, insertedAgo = Duration.ofDays(31), publishedAgo = Duration.ofDays(31))
            // The boundary row of INV-Q-4403: inserted just before the cut-off, published just after it,
            // within the margin.
            s.age(
                boundary,
                insertedAgo = Duration.ofDays(30).plusSeconds(5),
                publishedAgo = Duration.ofDays(30).minusMinutes(10),
            )
            s.purgeEventLog()
            val fault = s.write()
            s.markPublished(fault)

            val first = s.check.check()
            assertEquals(listOf(fault), first.open.map { it.outboxSeq }, "only the planted row is open")
            val second = s.check.check()
            assertEquals(listOf(fault), second.open.map { it.outboxSeq })
            s.newCheck().check() // another instance, or a restart: the row is still open but not raised again
            val finding = s.raised.single()
            assertEquals("canonical-svc", finding.service)
            assertEquals(topic, finding.topic)
            assertEquals(s.idOf(fault), finding.eventId)
            assertEquals(fault, finding.outboxSeq)
            assertTrue(relayed != fault)
        } finally {
            s.close()
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `a row written and marked published by a transaction open across a check is reported by the next`(
        image: String,
    ) {
        val s = setup(image, secondInstance = false)
        try {
            val written = CountDownLatch(1)
            val checked = CountDownLatch(1)
            var planted = 0L
            val open =
                thread {
                    s.scope.bindWorkload(TenantId(TENANT)) {
                        s.boundary.inTransaction {
                            s.append()
                            planted = s.lastSeq()
                            written.countDown()
                            checked.await()
                            s.mark(planted)
                        }
                    }
                }
            assertInvisibleToThisRun(s, written)
            checked.countDown()
            open.join()
            assertEquals(
                listOf(planted),
                s.check
                    .check()
                    .open
                    .map { it.outboxSeq },
                "the next run reports it",
            )
            assertEquals(listOf(planted), s.raised.map { it.outboxSeq })
        } finally {
            s.close()
        }
    }

    /** A later row is committed, relayed and checked while the planted row is invisible. */
    private fun assertInvisibleToThisRun(
        s: Setup,
        written: CountDownLatch,
    ) {
        written.await()
        s.write() // a later seq, committed and relayed while the planted row's transaction is open
        s.drain()
        assertTrue(
            s.check
                .check()
                .open
                .isEmpty(),
            "the planted row is invisible to this run",
        )
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `the outbox is read in bounded pages and each events_missing call holds at most the batch size`(image: String) {
        val s = setup(image, secondInstance = false)
        try {
            repeat(7) { s.write() }
            s.drain()
            val fault = s.write()
            s.markPublished(fault)
            val calls = mutableListOf<Int>()
            val real = PostgresEventLogPresence(s.eventLogScope, s.eventLogDsl)
            val counting =
                object : EventLogPresence {
                    override fun missing(
                        topic: String,
                        eventIds: Collection<String>,
                    ): MissingEvents {
                        calls += eventIds.size
                        return real.missing(topic, eventIds)
                    }
                }
            val check =
                JdbcUnrelayedEventCheck(
                    s.scope,
                    s.dsl,
                    SCHEMA,
                    "canonical-svc",
                    counting,
                    UnrelayedEventSettings(Duration.ofDays(30), margin, batchSize = 2),
                    UnrelayedEventSink { s.raised += it },
                    pageSize = 3,
                )
            assertEquals(listOf(fault), check.check().open.map { it.outboxSeq })
            assertEquals(8, calls.sum(), "every published row sent once")
            assertTrue(calls.all { it in 1..2 }, "at most the batch size per call: $calls")
            assertTrue(calls.size >= 4, "8 rows over pages of 3 in batches of 2: $calls")
            calls.clear()
            assertEquals(listOf(fault), check.check().open.map { it.outboxSeq })
            assertEquals(listOf(1), calls, "verified rows are not sent again; the open finding is")
        } finally {
            s.close()
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `events_missing answers only a topic's producers, only with missing ids and the cut-off`(image: String) {
        val s = setup(image, secondInstance = false)
        try {
            val seq = s.write()
            s.drain()
            val id = s.idOf(seq)
            val presence = PostgresEventLogPresence(s.eventLogScope, s.eventLogDsl)
            val answer = presence.missing(topic, listOf(id, "absent-1"))
            assertEquals(setOf("absent-1"), answer.missing)
            assertTrue(
                answer.cutoff.isBefore(
                    java.time.Instant
                        .now()
                        .minus(Duration.ofDays(29)),
                ),
            )
            // the id of this topic's event, asked on another topic the caller produces: missing there, nothing leaks
            assertEquals(setOf(id), presence.missing("gst.payload.payload-ready.v1", listOf(id)).missing)
            // a login producing no topic is refused
            val strangerScope =
                s.eventLog.eventLogScope(
                    TenantContextImpl(),
                    s.eventLog.rolePool("recon_runtime"),
                )
            val stranger = PostgresEventLogPresence(strangerScope, JooqKit.eventLog(strangerScope, catalogue))
            val refused = assertThrows(Exception::class.java) { stranger.missing(topic, listOf(id)) }
            assertEquals("42501", strangerScope.sqlErrors.sqlStateOf(refused))
            // more than 1,000 ids are refused by the function itself
            assertEquals("22023", oversizedAnswer(s.eventLog))
            assertCatalogue(s.eventLog)
        } finally {
            s.close()
        }
    }

    /** The SQLSTATE of `events_missing` asked for more ids than the function takes. */
    private fun oversizedAnswer(fixture: OutboxPostgresFixture): String? {
        val tooMany = Array(EventLogSchema.MAX_EVENTS_MISSING + 1) { "id-$it" }
        val failure =
            runCatching {
                JooqKit.Access.runtime(fixture, emptyMap()).single().asPlatform {
                    it
                        .select(
                            DSL.function(
                                DSL.name("eventlog", "events_missing"),
                                SQLDataType.OTHER,
                                DSL.`val`(topic, SQLDataType.CLOB),
                                DSL.`val`(tooMany, SQLDataType.CLOB.array()),
                            ),
                        ).fetch()
                }
            }.exceptionOrNull()
        return JooqKit.sqlState(failure)
    }

    /** INV-AC-2175 for `events_missing` and its producer table, read from the catalogue. */
    private fun assertCatalogue(fixture: OutboxPostgresFixture) {
        val index = topicTable.substringAfter('.') + "_event_id"

        fun list(sql: String): List<String> =
            TenancyProbe
                .connect(fixture.jdbcUrl(), OutboxPostgresFixture.APP, OutboxPostgresFixture.APP_PASSWORD)
                .unbound { fetchRows(sql) }
                .map { it.values.first() as String }
        assertEquals(
            listOf("eventlog_sys|true|search_path=pg_catalog, eventlog, pg_temp"),
            list(
                "select pg_get_userbyid(p.proowner) || '|' || p.prosecdef || '|' || " +
                    "array_to_string(p.proconfig, ';') " +
                    "from pg_proc p where p.pronamespace = 'eventlog'::regnamespace and p.proname = 'events_missing'",
            ),
        )
        assertEquals(
            listOf(OutboxPostgresFixture.APP),
            list(
                "select distinct pg_get_userbyid(a.grantee) from pg_proc p, aclexplode(p.proacl) a " +
                    "where p.pronamespace = 'eventlog'::regnamespace and p.proname = 'events_missing' " +
                    "and a.privilege_type = 'EXECUTE' and a.grantee <> p.proowner order by 1",
            ),
            "EXECUTE to the producers only, never PUBLIC",
        )
        assertEquals(
            listOf("eventlog_migrate|eventlog_sys|SELECT"),
            list(
                "select pg_get_userbyid(c.relowner) || '|' || pg_get_userbyid(a.grantee) || '|' || a.privilege_type " +
                    "from pg_class c, aclexplode(c.relacl) a where c.oid = 'eventlog.producer'::regclass " +
                    "and a.grantee <> c.relowner",
            ),
        )
        assertEquals(
            listOf(index),
            list("select indexname from pg_indexes where schemaname = 'eventlog' and indexname = '$index'"),
        )
    }

    companion object {
        private const val SCHEMA = "canonical"
        private val OUTBOX = DSL.table(DSL.name(SCHEMA, "outbox"))
        private val SEQ = DSL.field(DSL.name("seq"), SQLDataType.BIGINT)
        private val ID = DSL.field(DSL.name("id"), SQLDataType.CLOB)
        private val PUBLISHED_AT = DSL.field(DSL.name("published_at"), SQLDataType.TIMESTAMPWITHTIMEZONE)
        private val INSERTED_AT = DSL.field(DSL.name("inserted_at"), SQLDataType.TIMESTAMPWITHTIMEZONE)
        private val EVENT_ID = DSL.field(DSL.name("event_id"), SQLDataType.CLOB)
        private val TENANT: UUID = UUID.randomUUID()

        @JvmStatic
        fun topologies(): Stream<Arguments> =
            listOf("postgres:16", "postgres:17", "postgres:18")
                .flatMap { listOf(Arguments.of(it, false), Arguments.of(it, true)) }
                .stream()
    }
}
