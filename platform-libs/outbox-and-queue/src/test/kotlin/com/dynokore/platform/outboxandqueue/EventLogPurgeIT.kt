package com.dynokore.platform.outboxandqueue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The event-log purge and the purge watermark (Keel INV-T-3384; INV-REQ-1205 b3, b4, b6, boundaries.extreme,
 * must_not_happen; INV-AC-2175; owner ruling INV-Q-3249), on PostgreSQL 17 and 18: rows are deleted by age per topic
 * (content window for a content topic, event-log retention for the others) under the dedicated purge role, each batch
 * raising the topic's watermark in the same transaction; a consumer at or below the watermark stops that topic with a
 * 'rebuild required' incident and applies nothing, while a consumer ahead of it, and its other topics, continue.
 */
@Tag("integration")
class EventLogPurgeIT {
    private val content = "gst.canonical.canonical-version-created.v1"
    private val contentTable = "eventlog.gst_canonical_canonical_version_created_v1"
    private val other = "gst.submission.submission-acknowledged.v1"
    private val otherTable = "eventlog.gst_submission_submission_acknowledged_v1"
    private val despatched = "gst.submission.submission-despatched.v1"
    private val now = Instant.parse("2026-10-11T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val retention = Retention(contentWindow = Duration.ofDays(10), eventLogRetention = Duration.ofDays(30))

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `the purge removes only rows past each topic's period and raises the watermark with each batch`(image: String) {
        OutboxHarness(image).use { harness ->
            repeat(3) { emit(harness, TestCatalogue.CANONICAL_VERSION_CREATED) }
            repeat(5) { emit(harness, TestCatalogue.SUBMISSION_ACKNOWLEDGED) }
            // Content topic: offsets 1-2 are 11 days old (past the 10-day window), 3 is 9 days old.
            age(harness, contentTable, "case when log_offset <= 2 then 11 else 9 end")
            // Other topic: offsets 1-3 are 31 days old (past 30 days), 4-5 are 29 days old though past the window.
            age(harness, otherTable, "case when log_offset <= 3 then 31 else 29 end")

            val run = purge(harness, batchSize = 2).purge()

            val byTopic = run.topics.associateBy { it.topic }
            val window = Duration.ofDays(10)
            val retained = Duration.ofDays(30)
            assertEquals(TopicPurge(content, true, window, now - window, 2, 2), byTopic[content])
            assertEquals(TopicPurge(other, false, retained, now - retained, 3, 3), byTopic[other])
            assertEquals(TopicPurge(despatched, false, retained, now - retained, 0, 0), byTopic[despatched])
            assertEquals(listOf(3L), offsets(harness, contentTable))
            assertEquals(listOf(4L, 5L), offsets(harness, otherTable))
            assertEquals(mapOf(content to 2L, other to 3L), watermarks(harness), "no row for a topic never purged")

            assertEquals(0, purge(harness).purge().rows, "a second run finds nothing more")
            assertEquals(mapOf(content to 2L, other to 3L), watermarks(harness), "the watermark never moves back")
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a batch whose watermark cannot be written deletes nothing`(image: String) {
        OutboxHarness(image).use { harness ->
            repeat(2) { emit(harness, TestCatalogue.SUBMISSION_ACKNOWLEDGED) }
            age(harness, otherTable, "31")
            harness.fixture.runAsAdmin("revoke update on eventlog.purge_watermark from eventlog_purge")
            harness.fixture.runAsAdmin("insert into eventlog.purge_watermark values ('$other', 0, now())")

            assertThrows(EventBindingUnavailable::class.java) { purge(harness).purge() }
            assertEquals(listOf(1L, 2L), offsets(harness, otherTable), "the delete rolled back with the watermark")
            assertEquals(mapOf(other to 0L), watermarks(harness))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `the purge role deletes, sees only log_offset and inserted_at, holds no elevated attribute`(image: String) {
        OutboxHarness(image).use { harness ->
            emit(harness, TestCatalogue.SUBMISSION_ACKNOWLEDGED)
            harness.fixture.purgeDataSource().connection.use { purge ->
                purge.createStatement().use { statement ->
                    statement.executeQuery("select count(*) from $otherTable where inserted_at < now()").use {
                        it.next()
                        assertEquals(1, it.getInt(1), "every tenant's rows are visible to the purge")
                    }
                    for (column in listOf("envelope", "tenant_id")) {
                        val read = "select $column from $otherTable"
                        assertThrows(SQLException::class.java) { statement.executeQuery(read) }
                    }
                    assertThrows(SQLException::class.java) {
                        statement.executeUpdate("update $otherTable set inserted_at = now()")
                    }
                }
            }
            assertEquals(
                0,
                harness.count(
                    "select count(*) from pg_roles where rolname = 'eventlog_purge' " +
                        "and (rolsuper or rolbypassrls or rolcreaterole)",
                ),
            )
            // The consumers' roles read their topics' watermarks and cannot write them.
            harness.fixture.workerDataSource().connection.use { claim ->
                claim.createStatement().use { statement ->
                    statement.executeQuery("select count(*) from eventlog.purge_watermark").use { it.next() }
                    assertThrows(SQLException::class.java) {
                        statement.executeUpdate("insert into eventlog.purge_watermark values ('$other', 99, now())")
                    }
                }
            }
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
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
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a consumer role reads only the watermark rows of its own topics`(image: String) {
        OutboxHarness(image).use { harness ->
            val solo = "gst.solo.solo-event.v1"
            harness.fixture.runAsAdmin("create role solo_claim nologin; create role solo_reader nologin")
            harness.fixture.runAsAdmin("grant usage on schema eventlog to solo_claim, solo_reader")
            val roles = listOf(EventLogSchema.ConsumerRoles("solo_claim", "solo_reader"))
            harness.fixture.run(
                EventLogSchema.topicDdl(solo, OutboxPostgresFixture.RELAY, roles, OutboxPostgresFixture.PURGE),
            )
            harness.fixture.runAsAdmin(
                "insert into eventlog.purge_watermark values ('$solo', 4, now()), ('$other', 9, now())",
            )
            for (role in listOf("solo_claim", "solo_reader")) {
                assertEquals(mapOf(solo to 4L), watermarksAs(harness, role), role)
            }
        }
    }

    /** The watermark rows [role] can read (`set role` on a superuser connection, so its policies apply). */
    private fun watermarksAs(
        harness: OutboxHarness,
        role: String,
    ): Map<String, Long> = harness.fixture.adminDataSource().connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.execute("set role $role")
            statement.executeQuery("select topic, watermark from eventlog.purge_watermark").use { rows ->
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
            JdbcEventLogPurge(org.postgresql.ds.PGSimpleDataSource(), unmarked, Retention(), clock)
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

    private fun purge(
        harness: OutboxHarness,
        batchSize: Int = 1000,
    ) = JdbcEventLogPurge(harness.fixture.purgeDataSource(), harness.catalogue, retention, clock, batchSize)

    private fun emit(
        harness: OutboxHarness,
        eventType: String,
    ) {
        val kit =
            when (eventType) {
                TestCatalogue.CANONICAL_VERSION_CREATED -> harness.canonical
                else -> harness.submission
            }
        kit.inTx(ServiceKit.TENANT) {
            val context = kit.docContext(ServiceKit.TENANT, UUID.randomUUID())
            kit.writer.append(listOf(OutboundEvent(eventType, """{"n":1}""")), context)
        }
        harness.drain(kit.relay)
    }

    /** Sets `inserted_at` of every row of [table] to [days] (an SQL expression on `log_offset`) days before [now]. */
    private fun age(
        harness: OutboxHarness,
        table: String,
        days: String,
    ) = harness.fixture.runAsAdmin("update $table set inserted_at = timestamptz '$now' - ($days) * interval '1 day'")

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
                statement.executeQuery("select topic, watermark from eventlog.purge_watermark").use { rows ->
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
