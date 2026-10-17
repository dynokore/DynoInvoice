package com.dynokore.platform.outboxandqueue

import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.Query
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.OffsetDateTime
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
            rewrites(seq).forEach { (name, statement) ->
                assertEquals("42501", refusedAsRuntime(harness, statement), name)
            }
            // The trigger holds even where a privilege would not: the administrator cannot rewrite a row either.
            val admin =
                JooqKit.Access.admin(harness.fixture, TABLES).use { access ->
                    runCatching {
                        access.asPlatform {
                            it
                                .update(
                                    OUTBOX,
                                ).set(DATA, json("{}"))
                                .where(SEQ.eq(seq))
                                .execute()
                        }
                    }.exceptionOrNull()
                }
            assertEquals("42501", JooqKit.sqlState(admin))
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
            assertEquals(1, asRuntime(harness) { publish(it, seq, now().minusDays(BACKDATE_DAYS)).execute() })
            val fresh = marked(seq)
            assertEquals(1, harness.count(fresh), "a backdated first mark was stored")
            listOf<Pair<String, OffsetDateTime?>>(
                "now" to now(),
                "backdated" to now().minusDays(BACKDATE_DAYS),
                "null" to null,
            ).forEach { (name, value) ->
                assertEquals("42501", refusedAsRuntime(harness, { publish(it, seq, value) }), name)
            }
            assertEquals(1, harness.count(fresh))
            // Clearing an unpublished row's NULL is no publish either.
            val other = append(harness)
            assertEquals("42501", refusedAsRuntime(harness, { publish(it, other, null) }))
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

    private fun rewrites(seq: Long): List<Pair<String, (DSLContext) -> Query>> =
        listOf(
            "data" to { it.update(OUTBOX).set(DATA, json("{\"version\":2}")).where(SEQ.eq(seq)) },
            "actor" to {
                it
                    .update(OUTBOX)
                    .set(
                        DATA,
                        DSL.function(
                            DSL.name("pg_catalog", "jsonb_set"),
                            SQLDataType.JSONB,
                            DATA,
                            DSL.cast(DSL.inline("{actor}"), SQLDataType.CLOB.array()),
                            DSL.cast(DSL.inline("\"someone-else\""), SQLDataType.JSONB),
                        ),
                    ).where(SEQ.eq(seq))
            },
            "type" to { it.update(OUTBOX).set(TYPE, "com.dynokore.gst.payload.Other.v1").where(SEQ.eq(seq)) },
            "topic" to { it.update(OUTBOX).set(TOPIC, "gst.payload.other.v1").where(SEQ.eq(seq)) },
            "tenant" to { it.update(OUTBOX).set(TENANT_ID, UUID.randomUUID()).where(SEQ.eq(seq)) },
            "id" to { it.update(OUTBOX).set(ID, "forged").where(SEQ.eq(seq)) },
            "publish and data" to {
                it
                    .update(OUTBOX)
                    .set(PUBLISHED_AT, now())
                    .set(DATA, json("{}"))
                    .where(SEQ.eq(seq))
            },
            "delete" to { it.deleteFrom(OUTBOX).where(SEQ.eq(seq)) },
        )

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
        dsl: DSLContext,
        seq: Long,
        value: OffsetDateTime?,
    ) = dsl.update(OUTBOX).set(PUBLISHED_AT, value).where(SEQ.eq(seq))

    private fun json(text: String) = DSL.`val`(text, SQLDataType.JSONB)

    private fun now(): OffsetDateTime = OffsetDateTime.now()

    /** [block] on the runtime login in one committed transaction bound to the row's tenant. */
    private fun <T> asRuntime(
        harness: OutboxHarness,
        block: (DSLContext) -> T,
    ): T =
        JooqKit.Access
            .runtime(harness.fixture, TABLES)
            .single()
            .asTenant(ServiceKit.TENANT, block)

    private fun refusedAsRuntime(
        harness: OutboxHarness,
        statement: (DSLContext) -> Query,
    ): String? = JooqKit.sqlState(runCatching { asRuntime(harness) { statement(it).execute() } }.exceptionOrNull())

    private companion object {
        const val PAYLOAD_READY_TOPIC = "gst.payload.payload-ready.v1"
        const val RECENT = "published_at > now() - interval '1 minute'"
        const val BACKDATE_DAYS = 400L
        val TABLES = JooqKit.platformScoped("payload.outbox")
        val OUTBOX = DSL.table(DSL.name("payload", "outbox"))
        val SEQ = DSL.field(DSL.name("seq"), SQLDataType.BIGINT)
        val DATA = DSL.field(DSL.name("data"), SQLDataType.JSONB)
        val TYPE = DSL.field(DSL.name("type"), SQLDataType.CLOB)
        val TOPIC = DSL.field(DSL.name("topic"), SQLDataType.CLOB)
        val ID = DSL.field(DSL.name("id"), SQLDataType.CLOB)
        val TENANT_ID = DSL.field(DSL.name("tenant_id"), SQLDataType.UUID)
        val PUBLISHED_AT = DSL.field(DSL.name("published_at"), SQLDataType.TIMESTAMPWITHTIMEZONE)
    }
}
