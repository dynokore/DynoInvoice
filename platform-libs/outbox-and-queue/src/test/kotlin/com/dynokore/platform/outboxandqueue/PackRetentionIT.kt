package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantContextImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import java.time.Instant

/**
 * The pack artefact retention (INV-T-6291; INV-REQ-1210, INV-Q-6281; INV-REQ-1205 b4, b6): a topic marked
 * `retained_while_resolvable` keeps its events for `pack_retention` (1827 days), a content topic for the content
 * window and any other topic for the event-log retention.
 */
@Tag("integration")
class PackRetentionIT {
    private val marked = "gst.rules.rule-pack-staged.v1"
    private val markedTable = "eventlog.gst_rules_rule_pack_staged_v1"
    private val markedKey = "gst_rules_rule_pack_staged_v1"
    private val content = "gst.canonical.canonical-version-created.v1"
    private val contentTable = "eventlog.gst_canonical_canonical_version_created_v1"
    private val other = "gst.submission.submission-acknowledged.v1"
    private val otherTable = "eventlog.gst_submission_submission_acknowledged_v1"
    private val retention = Retention(Duration.ofDays(10), Duration.ofDays(30), Duration.ofDays(1827))

    /** The retention table as it was created before INV-T-6291 (no `pack_retention`). */
    private val previousDdl =
        """
        create table eventlog.retention (
            id boolean primary key default true check (id),
            content_window interval not null check (content_window >= interval '7 days'),
            eventlog_retention interval not null check (eventlog_retention >= interval '7 days'),
            updated_at timestamptz not null default now()
        );
        comment on table eventlog.retention is 'platform-scoped: no tenant data';
        revoke all on eventlog.retention from public;
        grant select on eventlog.retention to eventlog_sys;
        alter table eventlog.retention owner to eventlog_migrate;
        """.trimIndent()

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `a marked topic keeps its rows for the pack period, and the other topics keep theirs`(image: String) {
        OutboxHarness(image).use { harness ->
            harness.fixture.writeRetention(retention)
            repeat(3) { insert(harness, markedTable, "$it") }
            insert(harness, otherTable, "0")
            insert(harness, contentTable, "0")
            insert(harness, otherTable, "1")
            age(harness, markedTable, "case when log_offset = 1 then 31 when log_offset = 2 then 400 else 1828 end")
            age(harness, otherTable, "31")
            age(harness, contentTable, "11")
            val reader = harness.reader
            assertEquals(listOf(1L, 2L, 3L), reader.read(marked, 0, 10).map { it.offset }.take(3))

            val run = purge(harness).purge()

            val byTopic = run.topics.associateBy { it.topic }
            val pack = byTopic.getValue(marked)
            assertEquals(Duration.ofDays(1827), pack.period)
            assertTrue(pack.retainedWhileResolvable)
            assertEquals(false, pack.contentTopic, "purged_content stays false for a marked topic")
            assertEquals(1L, pack.rows)
            assertEquals(3L, pack.watermark)
            assertEquals(listOf(1L, 2L), offsets(harness, markedTable), "31 and 400 days survive, 1828 is purged")
            assertEquals(listOf<Long>(), offsets(harness, otherTable), "unmarked: 31 days purged")
            assertEquals(listOf<Long>(), offsets(harness, contentTable), "content: 11 days purged")
            assertEquals(Duration.ofDays(30), byTopic.getValue(other).period)
            assertEquals(false, byTopic.getValue(other).retainedWhileResolvable)
            assertEquals(Duration.ofDays(10), byTopic.getValue(content).period)
            val skew = Duration.between(pack.cutoff, byTopic.getValue(other).cutoff)
            assertEquals(Duration.ofDays(1797), skew.abs(), "cut-offs differ by the two periods")
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `a reader of the marked topic gets the old row until the purge and rebuild required after it`(image: String) {
        OutboxHarness(image).use { harness ->
            harness.fixture.writeRetention(retention)
            insert(harness, markedTable, "old")
            insert(harness, markedTable, "ancient")
            insert(harness, markedTable, "fresh")
            age(harness, markedTable, "case when log_offset = 1 then 400 when log_offset = 2 then 1828 else 1 end")
            val reader = harness.reader

            assertEquals(listOf(1L, 2L, 3L), reader.read(marked, 0, 10).map { it.offset })
            purge(harness).purge()
            assertThrows(RebuildRequired::class.java) { reader.read(marked, 0, 10) }
            val watermark = harness.reader.head(marked, 0).watermark
            assertEquals(2L, watermark)
            assertEquals(listOf(3L), reader.read(marked, watermark, 10).map { it.offset })
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `events_missing returns the pack cut-off for a marked topic and the event-log cut-off for another`(
        image: String,
    ) {
        OutboxHarness(image).use { harness ->
            harness.fixture.writeRetention(retention)
            val scope = harness.fixture.eventLogScope(TenantContextImpl(), harness.fixture.runtimePool())
            val presence = PostgresEventLogPresence(scope, JooqKit.eventLog(scope, harness.catalogue))
            val now = Instant.now()
            val pack = presence.missing(marked, listOf("x")).cutoff
            val ordinary = presence.missing(other, listOf("x")).cutoff
            assertTrue(Duration.between(pack, now.minus(Duration.ofDays(1827))).abs() < Duration.ofMinutes(2), "$pack")
            assertTrue(
                Duration.between(ordinary, now.minus(Duration.ofDays(30))).abs() < Duration.ofMinutes(2),
                "$ordinary",
            )
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `an installation with the previous retention table gains the column on the install step, which is idempotent`(
        image: String,
    ) {
        OutboxHarness(image).use { harness ->
            harness.fixture.runAsAdmin("drop table eventlog.retention")
            harness.fixture.runAsAdmin(previousDdl)
            harness.fixture.runAsAdmin(
                "insert into eventlog.retention (content_window, eventlog_retention) values ('10 days', '30 days')",
            )

            harness.fixture.writeRetention(Retention(Duration.ofDays(10), Duration.ofDays(30)))
            val first = row(harness)
            harness.fixture.writeRetention(Retention(Duration.ofDays(10), Duration.ofDays(30)))

            assertEquals(listOf(864000L, 2592000L, 157852800L), first.take(3))
            assertEquals(first.take(3), row(harness).take(3), "the second run changes nothing")
            assertEquals(1L, harness.count("select count(*) from eventlog.retention"))
            assertEquals(
                1L,
                harness.count(
                    "select count(*) from information_schema.columns " +
                        "where table_schema = 'eventlog' and table_name = 'retention' and column_name = 'pack_retention'",
                ),
            )
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `a pack retention below 1827 days is refused by the table, and a row below it deletes nothing`(image: String) {
        OutboxHarness(image).use { harness ->
            harness.fixture.writeRetention(retention)
            val migrate = OutboxPostgresFixture.MIGRATE to OutboxPostgresFixture.MIGRATE_PASSWORD
            val update = "update eventlog.retention set pack_retention = interval '1826 days' returning 1"
            assertTrue(JooqKit.refusal(harness.fixture, migrate.first, migrate.second, update) != null)

            insert(harness, markedTable, "a")
            insert(harness, otherTable, "b")
            age(harness, markedTable, "2000")
            age(harness, otherTable, "400")
            // the table check removed, as a damaged installation would be
            harness.fixture.runAsAdmin("alter table eventlog.retention drop constraint retention_pack_retention_check")
            harness.fixture.runAsAdmin("update eventlog.retention set pack_retention = interval '1826 days'")

            val run = purge(harness).purge()

            assertEquals(0, run.rows)
            assertEquals(listOf(1L), offsets(harness, markedTable))
            assertEquals(listOf(1L), offsets(harness, otherTable))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16"])
    fun `the purge refuses a topic without the marker and one marked both content and retained`(image: String) {
        val unmarked = EventCatalogue(listOf(CatalogueEntry("t.T.v1", other, setOf("submission-svc"), false)))
        val both =
            EventCatalogue(listOf(CatalogueEntry("t.T.v1", other, setOf("submission-svc"), true, false, true)))
        val twice =
            EventCatalogue(
                listOf(
                    CatalogueEntry("t.T.v1", other, setOf("submission-svc"), false, false, true),
                    CatalogueEntry("t.U.v1", other, setOf("submission-svc"), false, false, false),
                ),
            )
        OutboxHarness(image).use { harness ->
            val scope = harness.fixture.eventLogScope(TenantContextImpl(), harness.fixture.purgePool())
            val dsl = JooqKit.eventLog(scope, harness.catalogue)
            for (catalogue in listOf(unmarked, both, twice)) {
                val refused =
                    assertThrows(IllegalArgumentException::class.java) { JdbcEventLogPurge(scope, dsl, catalogue) }
                assertTrue(refused.message.orEmpty().contains(other), refused.message)
            }
            assertThrows(IllegalArgumentException::class.java) {
                EventLogSchema.Topic("gst.a.b.v1", setOf("a_runtime"), setOf("b_runtime"), true, null, true)
            }
        }
    }

    private fun row(harness: OutboxHarness): List<Long> =
        listOf("content_window", "eventlog_retention", "pack_retention").map {
            harness.count("select extract(epoch from $it)::bigint from eventlog.retention")
        } + listOf(harness.count("select count(*) from eventlog.retention"))

    private fun purge(harness: OutboxHarness): JdbcEventLogPurge {
        val scope = harness.fixture.eventLogScope(TenantContextImpl(), harness.fixture.purgePool())
        return JdbcEventLogPurge(scope, JooqKit.eventLog(scope, harness.catalogue), harness.catalogue)
    }

    private fun insert(
        harness: OutboxHarness,
        table: String,
        id: String,
    ) = harness.fixture.runAsAdmin(
        "insert into $table (tenant_id, event_id, subject, envelope) " +
            "values ('${ServiceKit.TENANT}', '$id-${System.nanoTime()}', 's', '{\"n\":1}')",
    )

    private fun age(
        harness: OutboxHarness,
        table: String,
        days: String,
    ) = harness.fixture.runAsAdmin("update $table set inserted_at = now() - ($days) * interval '1 day'")

    private fun offsets(
        harness: OutboxHarness,
        table: String,
    ): List<Long> =
        harness
            .rowsIn(ServiceKit.TENANT, "select log_offset from $table order by log_offset")
            .map { it.getValue("log_offset") as Long }
}
