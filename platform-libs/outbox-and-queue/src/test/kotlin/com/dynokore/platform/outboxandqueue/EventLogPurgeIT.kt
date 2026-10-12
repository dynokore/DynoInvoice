package com.dynokore.platform.outboxandqueue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.SQLException
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The event-log purge and the purge watermark (Keel INV-T-3384, INV-T-3979; INV-REQ-1205 b3, b4, b6,
 * boundaries.extreme, must_not_happen; INV-AC-2175; owner rulings INV-Q-3249, INV-Q-3953; coordinator rulings
 * INV-Q-4262, INV-Q-4264), on PostgreSQL 16, 17 and 18: `eventlog.purge_batch()`, owned by `eventlog_sys` and
 * executed by the purge login alone, deletes rows by age per topic (content window for a content topic, event-log
 * retention for the others, both from the retention row), never above a record topic's recorded position, each batch
 * raising the topic's watermark in the same transaction; with the retention row missing or below 7 days it deletes
 * nothing. A consumer at or below the watermark stops that topic with a 'rebuild required' incident and applies
 * nothing, while a consumer ahead of it, and its other topics, continue.
 */
@Tag("integration")
class EventLogPurgeIT {
    private val content = "gst.canonical.canonical-version-created.v1"
    private val contentKey = "gst_canonical_canonical_version_created_v1"
    private val contentTable = "eventlog.$contentKey"
    private val other = "gst.submission.submission-acknowledged.v1"
    private val otherKey = "gst_submission_submission_acknowledged_v1"
    private val otherTable = "eventlog.$otherKey"
    private val despatched = "gst.submission.submission-despatched.v1"
    private val audit = "gst.platform.audit-entry-recorded.v1"
    private val auditKey = "gst_platform_audit_entry_recorded_v1"
    private val auditTable = "eventlog.$auditKey"
    private val retention = Retention(contentWindow = Duration.ofDays(10), eventLogRetention = Duration.ofDays(30))

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `the purge removes only rows past each topic's period and raises the watermark with each batch`(image: String) {
        OutboxHarness(image).use { harness ->
            writeRetention(harness, retention)
            repeat(3) { emit(harness, TestCatalogue.CANONICAL_VERSION_CREATED) }
            repeat(5) { emit(harness, TestCatalogue.SUBMISSION_ACKNOWLEDGED) }
            // Content topic: offsets 1-2 are 11 days old (past the 10-day window), 3 is 9 days old.
            age(harness, contentTable, "case when log_offset <= 2 then 11 else 9 end")
            // Other topic: offsets 1-3 are 31 days old (past 30 days), 4-5 are 29 days old though past the window.
            age(harness, otherTable, "case when log_offset <= 3 then 31 else 29 end")

            val run = purge(harness).purge()

            val byTopic = run.topics.associateBy { it.topic }
            val window = Duration.ofDays(10)
            val retained = Duration.ofDays(30)
            assertEquals(listOf(content, true, window, 2L, 2L), shape(byTopic.getValue(content)))
            assertEquals(listOf(other, false, retained, 3L, 3L), shape(byTopic.getValue(other)))
            assertEquals(listOf(despatched, false, retained, 0L, 0L), shape(byTopic.getValue(despatched)))
            val cutoffs = Duration.between(byTopic.getValue(other).cutoff, byTopic.getValue(content).cutoff)
            assertEquals(retained.minus(window), cutoffs, "each cut-off is the call's now() less the topic's period")
            assertEquals(listOf(3L), offsets(harness, contentTable))
            assertEquals(listOf(4L, 5L), offsets(harness, otherTable))
            val purged = mapOf(contentKey to 2L, otherKey to 3L)
            assertEquals(purged, watermarks(harness), "no row for a topic never purged")

            assertEquals(0, purge(harness).purge().rows, "a second run finds nothing more")
            assertEquals(mapOf(contentKey to 2L, otherKey to 3L), watermarks(harness), "the watermark never moves back")
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `a purge never passes a record topic's recorded position, which only its keeper moves forward`(image: String) {
        OutboxHarness(image).use { harness ->
            repeat(4) { emit(harness, TestCatalogue.AUDIT_ENTRY_RECORDED) }
            age(harness, auditTable, "31")

            assertEquals(0, purge(harness).purge().topics.single { it.topic == audit }.rows, "position 0: nothing")
            assertEquals(listOf(1L, 2L, 3L, 4L), offsets(harness, auditTable))

            val positions = JdbcEventLogPositions(harness.fixture.eventLogDataSource())
            assertEquals(2L, positions.record(audit, 2))
            assertEquals(2L, positions.record(audit, 1), "a position never moves back")
            assertEquals(2, purge(harness).purge().topics.single { it.topic == audit }.rows)
            assertEquals(listOf(3L, 4L), offsets(harness, auditTable), "nothing above the recorded position")
            assertEquals(2L, watermarks(harness)[auditKey])

            // A login with EXECUTE that is not the keeper is refused by the function itself; one without is
            // refused too.
            harness.fixture.runAsAdmin(
                "grant execute on function eventlog.record_position(text, bigint) to recon_runtime",
            )
            val stranger = JdbcEventLogPositions(harness.fixture.roleDataSource("recon_runtime"))
            val refused = assertThrows(EventBindingUnavailable::class.java) { stranger.record(audit, 4) }
            assertEquals("42501", (refused.cause as SQLException).sqlState)
            val purgeLogin = JdbcEventLogPositions(harness.fixture.purgeDataSource())
            assertThrows(EventBindingUnavailable::class.java) { purgeLogin.record(audit, 4) }
            val position = "select position from eventlog.record_position where topic_key = ?"
            assertEquals(2L, harness.count(position, auditKey))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `with the retention row missing or below 7 days the purge deletes nothing, and no runtime login writes it`(
        image: String,
    ) {
        OutboxHarness(image).use { harness ->
            repeat(2) { emit(harness, TestCatalogue.SUBMISSION_ACKNOWLEDGED) }
            age(harness, otherTable, "400")

            // The table refuses a value below 7 days even from its owner.
            assertThrows(SQLException::class.java) {
                harness.fixture.runEventLog("update eventlog.retention set eventlog_retention = interval '6 days'")
            }
            for (login in listOf(harness.fixture.eventLogDataSource(), harness.fixture.purgeDataSource())) {
                login.connection.use { connection ->
                    assertThrows(SQLException::class.java, {
                        connection.createStatement().use {
                            it.executeUpdate("update eventlog.retention set eventlog_retention = interval '1 day'")
                        }
                    }, "a runtime write of the retention row")
                }
            }

            // Below 7 days (the CHECK dropped by the superuser, as a damaged installation would have it): nothing.
            harness.fixture.runAsAdmin(
                """
                do ${'$'}${'$'} declare c record; begin
                    for c in select conname from pg_constraint where conrelid = 'eventlog.retention'::regclass and contype = 'c'
                    loop execute format('alter table eventlog.retention drop constraint %I', c.conname); end loop;
                end ${'$'}${'$'};
                update eventlog.retention set eventlog_retention = interval '6 days';
                """.trimIndent(),
            )
            assertEquals(EventLogPurgeRun(emptyList()), purge(harness).purge())
            assertEquals(listOf(1L, 2L), offsets(harness, otherTable))

            // Missing: nothing either.
            harness.fixture.runAsAdmin("delete from eventlog.retention")
            assertEquals(EventLogPurgeRun(emptyList()), purge(harness).purge())
            assertEquals(listOf(1L, 2L), offsets(harness, otherTable))
            assertEquals(emptyMap<String, Long>(), watermarks(harness))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `a batch whose watermark cannot be written deletes nothing`(image: String) {
        OutboxHarness(image).use { harness ->
            repeat(2) { emit(harness, TestCatalogue.SUBMISSION_ACKNOWLEDGED) }
            age(harness, otherTable, "31")
            harness.fixture.runAsAdmin("revoke update on eventlog.purge_watermark from eventlog_sys")
            harness.fixture.runAsAdmin("insert into eventlog.purge_watermark values ('$otherKey', 0, now())")

            assertThrows(EventBindingUnavailable::class.java) { purge(harness).purge() }
            assertEquals(listOf(1L, 2L), offsets(harness, otherTable), "the delete rolled back with the watermark")
            assertEquals(mapOf(otherKey to 0L), watermarks(harness))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `the purge login only executes purge_batch and holds no elevated attribute`(image: String) {
        OutboxHarness(image).use { harness ->
            emit(harness, TestCatalogue.SUBMISSION_ACKNOWLEDGED)
            harness.fixture.purgeDataSource().connection.use { purge ->
                purge.createStatement().use { statement ->
                    for (sql in listOf(
                        "select log_offset from $otherTable",
                        "delete from $otherTable",
                        "select * from eventlog.claim('$other', 0, 1)",
                        "insert into eventlog.purge_watermark values ('$otherKey', 99, now())",
                    )) {
                        assertThrows(SQLException::class.java, { statement.execute(sql) }, sql)
                    }
                }
            }
            assertEquals(
                0,
                harness.count(
                    "select count(*) from pg_roles where rolname like 'eventlog_%' " +
                        "and (rolsuper or rolbypassrls or rolcreaterole)",
                ),
            )
            val sysNoLogin = "select count(*) from pg_roles where rolname = 'eventlog_sys' and not rolcanlogin"
            assertEquals(1, harness.count(sysNoLogin))
            val definers = setOf("claim", "purge_batch", "record_position")
            assertEquals(definers, definers(harness), "each owned by eventlog_sys")
            assertSubscriberCannotWriteWatermarks(harness)
        }
    }

    /** The SECURITY DEFINER functions of schema `eventlog` owned by `eventlog_sys`. */
    private fun definers(harness: OutboxHarness): Set<String> {
        val sql =
            "select p.proname from pg_proc p join pg_namespace n on n.oid = p.pronamespace " +
                "where n.nspname = 'eventlog' and p.prosecdef and pg_get_userbyid(p.proowner) = 'eventlog_sys'"
        return harness.fixture.adminDataSource().connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
            }
        }
    }

    /** A subscriber reads its topics' watermarks and cannot write them. */
    private fun assertSubscriberCannotWriteWatermarks(harness: OutboxHarness) {
        harness.fixture.eventLogDataSource().connection.use { claim ->
            claim.createStatement().use { statement ->
                statement.executeQuery("select count(*) from eventlog.purge_watermark").use { it.next() }
                assertThrows(SQLException::class.java) {
                    statement.executeUpdate("insert into eventlog.purge_watermark values ('$otherKey', 99, now())")
                }
            }
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `a consumer behind the watermark stops with rebuild required, others and other topics go on`(image: String) {
        OutboxHarness(image).use { harness ->
            val kit = harness.reconciliation
            val behindSeen = CopyOnWriteArrayList<String>()
            val aheadSeen = CopyOnWriteArrayList<String>()
            val behind = harness.consumer("behind-svc", other, "reconciliation", kit.boundary, recording(behindSeen))
            val ahead = harness.consumer("ahead-svc", other, "reconciliation", kit.boundary, recording(aheadSeen))
            val behindOther =
                harness.consumer("behind-svc", despatched, "reconciliation", kit.boundary, recording(behindSeen))

            repeat(4) { emit(harness, TestCatalogue.SUBMISSION_ACKNOWLEDGED) }
            assertEquals(4, ahead.pollOnce().applied)
            age(harness, otherTable, "case when log_offset <= 2 then 31 else 1 end")
            assertEquals(2, purge(harness).purge().rows)
            emit(harness, TestCatalogue.SUBMISSION_ACKNOWLEDGED)
            emit(harness, TestCatalogue.SUBMISSION_DESPATCHED)

            val stopped = behind.pollOnce()
            assertEquals(PollResult(0, 0, 0, 0, 0, rebuildRequired = true), stopped)
            assertTrue(behindSeen.isEmpty(), "nothing applied, no skip to offset 3")
            val incident = harness.rebuildIncidents.single()
            assertEquals(
                listOf("behind-svc", other, 1L, 2L),
                listOf(incident.consumer, incident.topic, incident.nextOffset, incident.watermark),
            )
            assertEquals(PollResult(0, 0, 0, 0, 0, rebuildRequired = true), behind.pollOnce(), "still stopped")
            assertEquals(1, harness.rebuildIncidents.size, "one incident until it resumes")
            assertEquals(1L, nextOffset(harness, "behind-svc", other), "the position did not move")

            assertEquals(1, ahead.pollOnce().applied, "a consumer ahead of the watermark continues")
            assertEquals(1, behindOther.pollOnce().applied, "the stopped consumer's other topic continues")

            // The recovery path rebuilds the read model and moves the position past the watermark: the topic resumes.
            harness.fixture.runAsAdmin(
                "insert into reconciliation.consumer_offset (consumer, topic, next_offset) " +
                    "values ('behind-svc', '$other', 3) on conflict (consumer, topic) do update set next_offset = 3",
            )
            behindSeen.clear()
            assertEquals(3, behind.pollOnce().applied)
            assertEquals(3, behindSeen.size)
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `a subscriber reads only the watermark rows of its own topics`(image: String) {
        OutboxHarness(image).use { harness ->
            val replayKey = "gst_canonical_replay_reconciliation_v1"
            harness.fixture.runAsAdmin(
                "insert into eventlog.purge_watermark values ('$replayKey', 4, now()), ('$otherKey', 9, now())",
            )
            assertEquals(mapOf(replayKey to 4L), watermarksAs(harness.fixture.roleDataSource("recon_runtime")))
            assertEquals(mapOf(otherKey to 9L), watermarksAs(harness.fixture.eventLogDataSource()))
        }
    }

    /** The watermark rows the login of [source] can read. */
    private fun watermarksAs(source: javax.sql.DataSource): Map<String, Long> = source.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("select topic_key, watermark from eventlog.purge_watermark").use { rows ->
                buildMap { while (rows.next()) put(rows.getString(1), rows.getLong(2)) }
            }
        }
    }

    @Test
    fun `a period below 7 days is refused, and so is a topic without a content marker`() {
        assertThrows(IllegalArgumentException::class.java) { Retention(contentWindow = Duration.ofDays(6)) }
        val belowFloor = Duration.ofDays(7).minusSeconds(1)
        assertThrows(IllegalArgumentException::class.java) { Retention(eventLogRetention = belowFloor) }
        val unmarked = EventCatalogue(listOf(CatalogueEntry("t.T.v1", other, setOf("submission-svc"))))
        assertThrows(IllegalArgumentException::class.java) {
            // The constructor refuses before any connection is taken: the data source points nowhere.
            JdbcEventLogPurge(org.postgresql.ds.PGSimpleDataSource(), unmarked)
        }
    }

    @Test
    fun `the topics yaml excerpt carries the real content_topic marker on every topic`() {
        val markers = TestCatalogue.load().entries().associate { it.topic to it.contentTopic }
        assertTrue(markers.values.none { it == null }, "every topic is marked: $markers")
        assertEquals(setOf(content, "gst.payload.payload-ready.v1"), markers.filterValues { it == true }.keys)
    }

    @Test
    fun `topics yaml content_topic marker is read, and a value other than true or false refused`() {
        val text =
            """
            topics:
              - topic: gst.a.b.v1
                event_type: x.B.v1
                producers:
                  - a-svc
                subscribers:
                  - c-svc
                content_topic: true
              - topic: gst.a.c.v1
                event_type: x.C.v1
                content_topic: false
                producers:
                  - a-svc
              - topic: gst.a.d.v1
                event_type: x.D.v1
                producers:
                  - a-svc
            """.trimIndent()
        val markers = TopicsYamlCatalogue.parse(text).entries().associate { it.topic to it.contentTopic }
        assertEquals(mapOf("gst.a.b.v1" to true, "gst.a.c.v1" to false, "gst.a.d.v1" to null), markers)
        assertEquals(setOf("a-svc"), TopicsYamlCatalogue.parse(text).find("x.C.v1")?.producers)
        assertThrows(IllegalArgumentException::class.java) {
            TopicsYamlCatalogue.parse(text.replace("content_topic: true", "content_topic: yes"))
        }
    }

    private fun purge(harness: OutboxHarness) = JdbcEventLogPurge(harness.fixture.purgeDataSource(), harness.catalogue)

    /** The migration step that rewrites the retention row (INV-Q-4262). */
    private fun writeRetention(
        harness: OutboxHarness,
        retention: Retention,
    ) = harness.fixture.migrateDataSource().connection.use { EventLogSchema.writeRetention(it, retention) }

    /** A purge result without its cut-off (taken from the database's clock). */
    private fun shape(purged: TopicPurge): List<Any> {
        val shape: List<Any> = listOf(purged.topic, purged.contentTopic, purged.period)
        return shape + listOf(purged.rows, purged.watermark)
    }

    private fun emit(
        harness: OutboxHarness,
        eventType: String,
    ) {
        val kit =
            when (eventType) {
                TestCatalogue.CANONICAL_VERSION_CREATED -> harness.canonical
                TestCatalogue.AUDIT_ENTRY_RECORDED -> harness.payload
                else -> harness.submission
            }
        kit.inTx(ServiceKit.TENANT) {
            val context = kit.docContext(ServiceKit.TENANT, UUID.randomUUID())
            kit.writer.append(listOf(OutboundEvent(eventType, """{"n":1}""")), context)
        }
        harness.drain(kit.relay)
    }

    /** Sets `inserted_at` of every row of [table] to [days] (an SQL expression on `log_offset`) days before now. */
    private fun age(
        harness: OutboxHarness,
        table: String,
        days: String,
    ) = harness.fixture.runAsAdmin("update $table set inserted_at = now() - ($days) * interval '1 day'")

    private fun offsets(
        harness: OutboxHarness,
        table: String,
    ): List<Long> = harness.fixture.adminDataSource().connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("select log_offset from $table order by log_offset").use { rows ->
                buildList { while (rows.next()) add(rows.getLong(1)) }
            }
        }
    }

    private fun watermarks(harness: OutboxHarness): Map<String, Long> {
        val admin = harness.fixture.adminDataSource()
        return admin.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("select topic_key, watermark from eventlog.purge_watermark").use { rows ->
                    buildMap { while (rows.next()) put(rows.getString(1), rows.getLong(2)) }
                }
            }
        }
    }

    private fun nextOffset(
        harness: OutboxHarness,
        consumer: String,
        topic: String,
    ): Long = harness.count(
        "select coalesce(max(next_offset), 1) from reconciliation.consumer_offset where consumer = ? and topic = ?",
        consumer,
        topic,
    )

    private fun recording(seen: MutableList<String>) = object : EventHandler {
        override fun handle(event: Envelope): HandleOutcome {
            seen += event.id.value
            return HandleOutcome.APPLIED
        }
    }
}
