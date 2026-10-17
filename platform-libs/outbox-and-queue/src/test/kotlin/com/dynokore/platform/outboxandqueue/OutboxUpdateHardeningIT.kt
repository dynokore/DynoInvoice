package com.dynokore.platform.outboxandqueue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

/**
 * Outbox UPDATE hardening (owner ruling INV-Q-4334 B), as the runtime login: a written row's content and actor are
 * never rewritten, `published_at` is set once and only to database time, so no unpublished row can be brought into
 * `outbox_purge_expired`'s range; the relay still publishes and marks rows. Marking an unrelayed row published at the
 * current time stays possible (the residual risk of INV-ADR-3968's consequences).
 */
@Tag("integration")
class OutboxUpdateHardeningIT {
    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `the runtime login cannot rewrite a row's content or actor, nor delete it`(image: String) {
        OutboxHarness(image).use { harness ->
            val seq = append(harness)
            listOf(
                "update payload.outbox set data = '{\"version\":2}' where seq = $seq",
                "update payload.outbox set data = jsonb_set(data, '{actor}', '\"someone-else\"') where seq = $seq",
                "update payload.outbox set type = 'com.dynokore.gst.payload.Other.v1' where seq = $seq",
                "update payload.outbox set topic = 'gst.payload.other.v1' where seq = $seq",
                "update payload.outbox set tenant_id = '${UUID.randomUUID()}' where seq = $seq",
                "update payload.outbox set id = 'forged' where seq = $seq",
                "update payload.outbox set published_at = now(), data = '{}' where seq = $seq",
                "delete from payload.outbox where seq = $seq",
            ).forEach { sql -> assertEquals("42501", refusedAsRuntime(harness, sql), sql) }
            // The trigger holds even where a privilege would not: the administrator cannot rewrite a row either.
            val admin =
                assertThrows(SQLException::class.java) {
                    harness.fixture.runAsAdmin("update payload.outbox set data = '{}' where seq = $seq")
                }
            assertEquals("42501", admin.sqlState)
            val unchanged = "select count(*) from payload.outbox where seq = $seq and data = '{\"version\":1}'"
            assertEquals(1, harness.count(unchanged))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `published_at is set once to database time and never re-set, cleared or backdated`(image: String) {
        OutboxHarness(image).use { harness ->
            val seq = append(harness)
            // From NULL, a passed past time is ignored: the trigger stores clock_timestamp(), out of the purge's range.
            assertEquals(1, asRuntime(harness) { it.executeUpdate(publish(seq, "now() - interval '400 days'")) })
            val fresh = marked(seq)
            assertEquals(1, harness.count(fresh), "a backdated first mark was stored")
            listOf(
                publish(seq, "now()"),
                publish(seq, "now() - interval '400 days'"),
                publish(seq, "null"),
            ).forEach { sql -> assertEquals("42501", refusedAsRuntime(harness, sql), sql) }
            assertEquals(1, harness.count(fresh))
            // Clearing an unpublished row's NULL is no publish either.
            val other = append(harness)
            assertEquals("42501", refusedAsRuntime(harness, publish(other, "null")))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `the relay still publishes and marks rows`(image: String) {
        OutboxHarness(image).use { harness ->
            val seq = append(harness)
            harness.drain(harness.payload.relay)
            assertEquals(1, harness.logOf(PAYLOAD_READY_TOPIC).size)
            assertEquals(1, harness.count(marked(seq)), "the relay's mark is the database time")
        }
    }

    private fun append(harness: OutboxHarness): Long {
        val kit = harness.payload
        val doc = UUID.randomUUID()
        kit.inTx(ServiceKit.TENANT) {
            kit.writer.append(
                listOf(OutboundEvent(TestCatalogue.PAYLOAD_READY, """{"version":1}""")),
                kit.docContext(ServiceKit.TENANT, doc),
            )
        }
        return harness.count("select seq from payload.outbox where subject = '$doc'")
    }

    private fun marked(seq: Long) = "select count(*) from payload.outbox where seq = $seq and $RECENT"

    private fun publish(
        seq: Long,
        value: String,
    ) = "update payload.outbox set published_at = $value where seq = $seq"

    /** [block] on the runtime login in one committed transaction bound to the row's tenant. */
    private fun <T> asRuntime(
        harness: OutboxHarness,
        block: (java.sql.Statement) -> T,
    ): T =
        harness.fixture.runtimeDataSource().connection.use { c: Connection ->
            c.autoCommit = false
            c.prepareStatement("select set_config('app.tenant_id', ?, true)").use {
                it.setString(1, ServiceKit.TENANT.toString())
                it.executeQuery().close()
            }
            try {
                c.createStatement().use(block).also { c.commit() }
            } catch (failure: SQLException) {
                c.rollback()
                throw failure
            }
        }

    private fun refusedAsRuntime(
        harness: OutboxHarness,
        sql: String,
    ): String? = assertThrows(SQLException::class.java, { asRuntime(harness) { it.executeUpdate(sql) } }, sql).sqlState

    private companion object {
        const val PAYLOAD_READY_TOPIC = "gst.payload.payload-ready.v1"
        const val RECENT = "published_at > now() - interval '1 minute'"
    }
}
