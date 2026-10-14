package com.dynokore.platform.outboxandqueue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.sql.DataSource

/** INV-T-2998: the purge of published outbox rows (content window) and inbox rows (event-log retention + 1 day). */
@Tag("integration")
class RetentionPurgeIT {
    // a minute behind the database's now(), which the purge functions' retention floor reads (INV-Q-4638)
    private val now = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.SECONDS)
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val tenantA = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val tenantB = UUID.fromString("00000000-0000-0000-0000-00000000000b")

    @Test
    fun `periods default to 30 days and are refused below 7`() {
        assertEquals(Duration.ofDays(30), Retention().contentWindow)
        assertEquals(Duration.ofDays(31), Retention().inboxRetention)
        val week = Duration.ofDays(7)
        val forty = Duration.ofDays(40)
        assertEquals(Duration.ofDays(41), Retention(forty, week).inboxRetention, "content window longer")
        assertEquals(Duration.ofDays(41), Retention(week, forty).inboxRetention, "event-log retention longer")
        Retention(Duration.ofDays(7), Duration.ofDays(7))
        val belowFloor = Duration.ofDays(7).minusSeconds(1)
        assertThrows(IllegalArgumentException::class.java) { Retention(contentWindow = belowFloor) }
        assertThrows(IllegalArgumentException::class.java) { Retention(eventLogRetention = belowFloor) }
    }

    private fun purger(
        harness: OutboxHarness,
        retention: Retention,
        batchSize: Int = 1000,
    ) = JdbcRetentionPurge(harness.fixture.runtimeDataSource(), "payload", retention, clock, batchSize)

    /** A pool that hands out connections with autocommit off, as Hikari can be configured to. */
    private fun manualCommit(source: DataSource): DataSource =
        object : DataSource by source {
            override fun getConnection(): Connection = source.connection.also { it.autoCommit = false }
        }

    private fun outbox(
        harness: OutboxHarness,
        id: String,
        tenant: UUID,
        publishedDaysAgo: Long?,
    ) {
        val published = publishedDaysAgo?.let { "timestamptz '$now' - interval '$it days'" } ?: "null"
        harness.fixture.runAsAdmin(
            """insert into payload.outbox (id, topic, type, source, subject, event_time, tenant_id, entity_ref,
            instance_id, correlation_id, causation_id, data, published_at)
            values ('$id', 't', 'ty', 's', 'sub', now(), '$tenant', 'e', 'i', 'c', 'c', '{}', $published)""",
        )
    }

    private fun inbox(
        harness: OutboxHarness,
        id: String,
        tenant: UUID,
        hoursAgo: Long,
    ) = harness.fixture.runAsAdmin(
        "insert into payload.inbox (consumer, event_id, tenant_id, received_at) values " +
            "('c', '$id', '$tenant', timestamptz '$now' - interval '$hoursAgo hours')",
    )

    private fun ids(
        harness: OutboxHarness,
        table: String,
        column: String,
    ): Set<String> =
        harness.fixture.adminDataSource().connection.use { c ->
            c.createStatement().use { st ->
                st.executeQuery("select $column from payload.$table").use { rs ->
                    buildSet { while (rs.next()) add(rs.getString(1)) }
                }
            }
        }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `purge deletes published outbox rows past the window, never an unpublished one`(image: String) {
        OutboxHarness(image).use { harness ->
            outbox(harness, "old-a", tenantA, 31)
            outbox(harness, "old-b", tenantB, 40)
            outbox(harness, "recent", tenantA, 29)
            outbox(harness, "unpublished-ancient", tenantA, null)
            // Rows are never rewritten (publish-once trigger, INV-Q-4334): the administrator ages them with
            // triggers off for its own session only.
            harness.fixture.runAsAdmin(
                "set session_replication_role = replica; " +
                    "update payload.outbox set event_time = now() - interval '400 days'; " +
                    "reset session_replication_role",
            )
            val purge = purger(harness, Retention(), 1)
            assertEquals(PurgeResult(outboxRows = 2, inboxRows = 0), purge.purge())
            assertEquals(setOf("recent", "unpublished-ancient"), ids(harness, "outbox", "id"))
            assertEquals(PurgeResult(0, 0), purge.purge(), "nothing more to delete")

            val sevenDays = purger(harness, Retention(Duration.ofDays(7)))
            assertEquals(1, sevenDays.purge().outboxRows)
            assertEquals(setOf("unpublished-ancient"), ids(harness, "outbox", "id"))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `purge deletes inbox rows older than event-log retention plus one day`(image: String) {
        OutboxHarness(image).use { harness ->
            inbox(harness, "old", tenantA, 31 * 24 + 1)
            inbox(harness, "old-b", tenantB, 60 * 24)
            inbox(harness, "inside-margin", tenantA, 31 * 24 - 1)
            inbox(harness, "recent", tenantB, 1)
            val purge = purger(harness, Retention())
            assertEquals(PurgeResult(0, 2), purge.purge())
            assertEquals(setOf("inside-margin", "recent"), ids(harness, "inbox", "event_id"))

            val shorter = Retention(contentWindow = Duration.ofDays(7), eventLogRetention = Duration.ofDays(7))
            assertEquals(1, purger(harness, shorter).purge().inboxRows)
            assertEquals(setOf("recent"), ids(harness, "inbox", "event_id").also { assertTrue(it.isNotEmpty()) })
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a content window longer than event-log retention keeps the inbox row while a content event is in the log`(
        image: String,
    ) {
        OutboxHarness(image).use { harness ->
            // A content-topic event 31 days old is still in the log under a 40-day window, so its inbox row stays.
            inbox(harness, "content-still-in-log", tenantA, 31 * 24)
            inbox(harness, "past-window", tenantB, 41 * 24 + 1)
            val retention = Retention(contentWindow = Duration.ofDays(40), eventLogRetention = Duration.ofDays(7))
            assertEquals(1, purger(harness, retention).purge().inboxRows)
            assertEquals(setOf("content-still-in-log"), ids(harness, "inbox", "event_id"))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `the runtime login deletes nothing directly and a purge function deletes only expired rows`(image: String) {
        OutboxHarness(image).use { harness ->
            outbox(harness, "unpublished", tenantA, null)
            outbox(harness, "published", tenantA, 1)
            outbox(harness, "expired", tenantB, 40)
            harness.fixture.runtimeDataSource().connection.use { c ->
                c.createStatement().use {
                    assertThrows(SQLException::class.java) { it.executeUpdate("delete from payload.outbox") }
                }
                c.createStatement().use {
                    it.execute("select set_config('app.tenant_id', '$tenantA', false)")
                    assertThrows(SQLException::class.java) { it.executeUpdate("delete from payload.outbox") }
                    assertThrows(SQLException::class.java) { it.executeUpdate("delete from payload.inbox") }
                    assertThrows(SQLException::class.java) { it.executeUpdate("delete from payload.event_dead_letter") }
                }
                // The floor is in SQL: a cut-off later than now - 7 days (8 for the inbox) is refused.
                listOf("outbox_purge_expired", "event_dead_letter_purge_expired").forEach { function ->
                    assertThrows(SQLException::class.java, {
                        c.createStatement().use {
                            it.executeQuery("select payload.$function(now() - interval '6 days', 10)")
                        }
                    }, function)
                }
                assertThrows(SQLException::class.java) {
                    c.createStatement().use {
                        it.executeQuery("select payload.inbox_purge_expired(now() - interval '7 days', 10)")
                    }
                }
                c.createStatement().use {
                    it.executeQuery("select payload.outbox_purge_expired(now() - interval '7 days', 10)").use { rows ->
                        rows.next()
                        assertEquals(1, rows.getLong(1), "only the expired published row, of any tenant")
                    }
                }
            }
            assertEquals(setOf("unpublished", "published"), ids(harness, "outbox", "id"))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a purge on an autocommit-off pool still commits every batch`(image: String) {
        OutboxHarness(image).use { harness ->
            outbox(harness, "old-a", tenantA, 40)
            outbox(harness, "old-b", tenantB, 40)
            outbox(harness, "old-c", tenantA, 40)
            inbox(harness, "old", tenantA, 40 * 24)
            val pool = manualCommit(harness.fixture.runtimeDataSource())
            val purge = JdbcRetentionPurge(pool, "payload", Retention(), clock, 2)
            assertEquals(PurgeResult(3, 1), purge.purge())
            assertEquals(emptySet<String>(), ids(harness, "outbox", "id"))
            assertEquals(emptySet<String>(), ids(harness, "inbox", "event_id"))
        }
    }

    private fun deadLetter(
        harness: OutboxHarness,
        tenant: UUID?,
        status: String,
        resolvedDaysAgo: Long?,
    ) {
        val resolved = resolvedDaysAgo?.let { "timestamptz '$now' - interval '$it days'" } ?: "null"
        harness.fixture.runAsAdmin(
            "insert into payload.event_dead_letter " +
                "(consumer, topic, log_offset, tenant_id, reason, raw, status, resolved_at) " +
                "values ('c', 't', 1, ${tenant?.let { "'$it'" } ?: "null"}, 'r', '{}', '$status', $resolved)",
        )
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `triaged dead letters expire past the content window from resolved_at and open ones never do`(image: String) {
        OutboxHarness(image).use { harness ->
            deadLetter(harness, tenantA, "REPLAYED", 40) // expires
            deadLetter(harness, null, "DISCARDED", 40) // tenantless expires too
            deadLetter(harness, tenantB, "DISCARDED", 10) // inside the window
            deadLetter(harness, tenantA, "OPEN", null) // open, never
            val purge = purger(harness, Retention())
            assertEquals(PurgeResult(0, 0, 2), purge.purge())
            assertEquals(2L, harness.count("select count(*) from payload.event_dead_letter"))
            assertEquals(1L, harness.count("select count(*) from payload.event_dead_letter where status = 'OPEN'"))
            assertEquals(PurgeResult(0, 0, 0), purge.purge())
            // A shorter window (the floor) expires the 10-day letter too; the open one still stays.
            val floor = Retention(Duration.ofDays(7), Duration.ofDays(7))
            assertEquals(PurgeResult(0, 0, 1), purger(harness, floor).purge())
            assertEquals(1L, harness.count("select count(*) from payload.event_dead_letter"))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `an open dead letter is never purged, and the runtime login cannot delete one even with a tenant bound`(
        image: String,
    ) {
        OutboxHarness(image).use { harness ->
            deadLetter(harness, tenantA, "OPEN", null)
            harness.fixture.runtimeDataSource().connection.use { c ->
                c.createStatement().use { s ->
                    s.execute("select set_config('app.tenant_id', '$tenantA', false)")
                    assertThrows(SQLException::class.java) { s.executeUpdate("delete from payload.event_dead_letter") }
                }
                c.createStatement().use { s ->
                    val purge = "select payload.event_dead_letter_purge_expired(now() - interval '7 days', 10)"
                    s.executeQuery(purge).use {
                        it.next()
                        assertEquals(0, it.getLong(1))
                    }
                }
            }
            assertEquals(1L, harness.count("select count(*) from payload.event_dead_letter"))
        }
    }
}
