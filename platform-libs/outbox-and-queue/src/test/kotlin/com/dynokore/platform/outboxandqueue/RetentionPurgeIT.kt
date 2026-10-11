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
import java.util.UUID
import javax.sql.DataSource

/** INV-T-2998: the purge of published outbox rows (content window) and inbox rows (event-log retention + 1 day). */
@Tag("integration")
class RetentionPurgeIT {
    private val now = Instant.parse("2026-10-11T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val tenantA = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val tenantB = UUID.fromString("00000000-0000-0000-0000-00000000000b")

    @Test
    fun `periods default to 30 days and are refused below 7`() {
        assertEquals(Duration.ofDays(30), Retention().contentWindow)
        assertEquals(Duration.ofDays(31), Retention().inboxRetention)
        Retention(Duration.ofDays(7), Duration.ofDays(7))
        val belowFloor = Duration.ofDays(7).minusSeconds(1)
        assertThrows(IllegalArgumentException::class.java) { Retention(contentWindow = belowFloor) }
        assertThrows(IllegalArgumentException::class.java) { Retention(eventLogRetention = belowFloor) }
    }

    private fun purger(
        harness: OutboxHarness,
        retention: Retention,
        batchSize: Int = 1000,
    ) = JdbcRetentionPurge(harness.fixture.workerDataSource(), "payload", retention, clock, batchSize)

    /** A pool that hands out connections with autocommit off, as Hikari can be configured to. */
    private fun manualCommit(source: DataSource): DataSource = object : DataSource by source {
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
    ): Set<String> = harness.fixture.adminDataSource().connection.use { c ->
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
            harness.fixture.runAsAdmin("update payload.outbox set event_time = now() - interval '400 days'")
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

            val shorter = Retention(eventLogRetention = Duration.ofDays(7))
            assertEquals(1, purger(harness, shorter).purge().inboxRows)
            assertEquals(setOf("recent"), ids(harness, "inbox", "event_id").also { assertTrue(it.isNotEmpty()) })
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `the worker can delete nothing else and the app role cannot purge`(image: String) {
        OutboxHarness(image).use { harness ->
            outbox(harness, "unpublished", tenantA, null)
            outbox(harness, "published", tenantA, 1)
            harness.fixture.workerDataSource().connection.use { c ->
                c.createStatement().use {
                    assertEquals(1, it.executeUpdate("delete from payload.outbox"), "only the published row")
                }
                assertThrows(SQLException::class.java) {
                    c.createStatement().use { it.executeUpdate("delete from payload.outbox_sequence") }
                }
                assertThrows(SQLException::class.java) {
                    c.createStatement().use { it.executeQuery("select data from payload.outbox") }
                }
            }
            assertEquals(setOf("unpublished"), ids(harness, "outbox", "id"))
            harness.fixture.workerDataSource().connection.use { c ->
                c.createStatement().use {
                    it.execute("select set_config('app.tenant_id', '$tenantA', false)")
                    assertEquals(0, it.executeUpdate("delete from payload.outbox"), "a tenant-bound worker too")
                }
            }
            assertEquals(setOf("unpublished"), ids(harness, "outbox", "id"))
            harness.fixture.appDataSource().connection.use { c ->
                assertThrows(SQLException::class.java) {
                    c.createStatement().use { it.executeUpdate("delete from payload.inbox") }
                }
            }
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
            val pool = manualCommit(harness.fixture.workerDataSource())
            val purge = JdbcRetentionPurge(pool, "payload", Retention(), clock, 2)
            assertEquals(PurgeResult(3, 1), purge.purge())
            assertEquals(emptySet<String>(), ids(harness, "outbox", "id"))
            assertEquals(emptySet<String>(), ids(harness, "inbox", "event_id"))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `upgradeTo130 takes a real 1_2_0 schema from no purge access to purge access, twice over`(image: String) {
        OutboxPostgresFixture(image).use { fixture ->
            fixture.runAsAdmin("create schema legacy authorization outbox_owner")
            fixture.run(OutboxSchema120.ddl("legacy", OutboxPostgresFixture.APP, OutboxPostgresFixture.WORKER))
            fixture.runAsAdmin(
                "insert into legacy.inbox (consumer, event_id, tenant_id, received_at) values " +
                    "('c', 'old', '$tenantA', timestamptz '$now' - interval '40 days')",
            )
            val purge = JdbcRetentionPurge(fixture.workerDataSource(), "legacy", Retention(), clock)
            assertThrows(SQLException::class.java) { purge.purge() }

            val upgrade = OutboxSchema.upgradeTo130("legacy", OutboxPostgresFixture.WORKER)
            fixture.run(upgrade)
            fixture.run(upgrade)
            assertEquals(PurgeResult(0, 1), purge.purge())
        }
    }
}
