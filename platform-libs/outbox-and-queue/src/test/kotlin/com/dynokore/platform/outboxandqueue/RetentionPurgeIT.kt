package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantContextImpl
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
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
    private val platform = "ffffffff-ffff-ffff-ffff-ffffffffffff"

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
        source: DataSource = harness.fixture.runtimePool(),
    ): JdbcRetentionPurge {
        val scope = harness.fixture.eventLogScope(TenantContextImpl(), source)
        val dsl = JooqKit.module("payload", scope.tenants, scope.transactions)
        return JdbcRetentionPurge(scope, dsl, "payload", retention, clock, batchSize)
    }

    /** A pool that hands out connections with autocommit off, as Hikari can be configured to. */
    private fun manualCommit(source: DataSource): DataSource =
        object : DataSource by source {
            override fun getConnection(): Connection = source.connection.also { it.autoCommit = false }
        }

    private fun tables() = OutboxSchema.jooqTables("payload")

    private fun at(instant: Instant): OffsetDateTime = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)

    /**
     * An outbox row of [tenant] written by the runtime login under that tenant (route R4), with `event_time` 400 days
     * old from the start, so no superuser rewrite of a published row is needed (publish-once trigger, INV-Q-4334).
     */
    private fun outbox(
        harness: OutboxHarness,
        id: String,
        tenant: UUID,
        publishedDaysAgo: Long?,
    ) {
        val published = publishedDaysAgo?.let { at(now.minus(it, ChronoUnit.DAYS)) }
        val text = SQLDataType.CLOB
        JooqKit.Access.runtime(harness.fixture, tables()).single().asTenant(tenant) {
            JooqKit.insert(
                it,
                "payload",
                OutboxSchema.OUTBOX,
                JooqKit.Col("id", text, id),
                JooqKit.Col("topic", text, "t"),
                JooqKit.Col("type", text, "ty"),
                JooqKit.Col("source", text, "s"),
                JooqKit.Col("subject", text, "sub"),
                JooqKit.Col(
                    "event_time",
                    SQLDataType.TIMESTAMPWITHTIMEZONE,
                    at(Instant.now().minus(400, ChronoUnit.DAYS)),
                ),
                JooqKit.Col("tenant_id", SQLDataType.UUID, tenant),
                JooqKit.Col("entity_ref", text, "e"),
                JooqKit.Col("instance_id", text, "i"),
                JooqKit.Col("correlation_id", text, "c"),
                JooqKit.Col("causation_id", text, "c"),
                JooqKit.ExprCol("data", SQLDataType.JSONB, DSL.jsonbObject()),
                JooqKit.Col("published_at", SQLDataType.TIMESTAMPWITHTIMEZONE, published),
            )
        }
    }

    private fun inbox(
        harness: OutboxHarness,
        id: String,
        tenant: UUID,
        hoursAgo: Long,
    ) = JooqKit.Access.runtime(harness.fixture, tables()).single().asTenant(tenant) {
        JooqKit.insert(
            it,
            "payload",
            OutboxSchema.INBOX,
            JooqKit.Col("consumer", SQLDataType.CLOB, "c"),
            JooqKit.Col("event_id", SQLDataType.CLOB, id),
            JooqKit.Col("tenant_id", SQLDataType.UUID, tenant),
            JooqKit.Col("received_at", SQLDataType.TIMESTAMPWITHTIMEZONE, at(now.minus(hoursAgo, ChronoUnit.HOURS))),
        )
    }

    /** The values of [column] in `payload.table`, read per tenant by the runtime login (RLS: own rows only). */
    private fun ids(
        harness: OutboxHarness,
        table: String,
        column: String,
    ): Set<String> =
        listOf(tenantA, tenantB)
            .flatMap { tenant -> harness.rowsIn(tenant, "select $column from payload.$table") }
            .map { it.getValue(column) as String }
            .toSet()

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `purge deletes published outbox rows past the window, never an unpublished one`(image: String) {
        OutboxHarness(image).use { harness ->
            outbox(harness, "old-a", tenantA, 31)
            outbox(harness, "old-b", tenantB, 40)
            outbox(harness, "recent", tenantA, 29)
            outbox(harness, "unpublished-ancient", tenantA, null)
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
            val app = OutboxPostgresFixture.APP
            val pw = OutboxPostgresFixture.APP_PASSWORD

            fun refused(
                sql: String,
                tenant: UUID? = null,
            ) = assertNotNull(JooqKit.refusal(harness.fixture, app, pw, sql, tenant), sql)
            refused("delete from payload.outbox returning 1")
            refused("delete from payload.outbox returning 1", tenantA)
            refused("delete from payload.inbox returning 1", tenantA)
            refused("delete from payload.event_dead_letter returning 1", tenantA)
            // The floor is in SQL: a cut-off later than now - 7 days (8 for the inbox) is refused.
            listOf("outbox_purge_expired", "event_dead_letter_purge_expired").forEach { function ->
                refused("select payload.$function(now() - interval '6 days', 10)")
            }
            refused("select payload.inbox_purge_expired(now() - interval '7 days', 10)")
            val expired =
                harness.rowsUnboundAs(
                    app,
                    pw,
                    "select payload.outbox_purge_expired(now() - interval '7 days', 10) as n",
                )
            assertEquals(1L, expired.single().getValue("n"), "only the expired published row, of any tenant")
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
            val pool = manualCommit(harness.fixture.runtimePool())
            val purge = purger(harness, Retention(), 2, pool)
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
        val owner = JooqKit.Access.owner(harness.fixture, tables()).single()
        val text = SQLDataType.CLOB
        val resolved = resolvedDaysAgo?.let { at(now.minus(it, ChronoUnit.DAYS)) }
        val letter = { dsl: DSLContext ->
            JooqKit.insert(
                dsl,
                "payload",
                OutboxSchema.DEAD_LETTER,
                JooqKit.Col("consumer", text, "c"),
                JooqKit.Col("topic", text, "t"),
                JooqKit.Col("log_offset", SQLDataType.BIGINT, 1L),
                JooqKit.Col("tenant_id", SQLDataType.UUID, tenant ?: UUID.fromString(platform)),
                JooqKit.Col("reason", text, "r"),
                JooqKit.Col("raw", text, "{}"),
                JooqKit.Col("status", text, status),
                JooqKit.Col("resolved_at", SQLDataType.TIMESTAMPWITHTIMEZONE, resolved),
            )
        }
        // the owner login holds the right to write a resolved letter; a tenantless letter is the platform tenant's
        if (tenant == null) owner.asPlatform(letter) else owner.asTenant(tenant, letter)
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
            val app = OutboxPostgresFixture.APP
            val pw = OutboxPostgresFixture.APP_PASSWORD
            val sql = "delete from payload.event_dead_letter returning 1"
            assertNotNull(JooqKit.refusal(harness.fixture, app, pw, sql, tenantA), sql)
            val purged =
                harness.rowsUnboundAs(
                    app,
                    pw,
                    "select payload.event_dead_letter_purge_expired(now() - interval '7 days', 10) as n",
                )
            assertEquals(0L, purged.single().getValue("n"))
            assertEquals(1L, harness.count("select count(*) from payload.event_dead_letter"))
        }
    }
}
