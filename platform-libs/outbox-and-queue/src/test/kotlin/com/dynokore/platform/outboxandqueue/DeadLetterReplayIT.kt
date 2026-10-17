package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancyjooq.TenancyProbe
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Dead-letter triage and replay by scope to one named consumer. Dead letters are content under forced RLS: the worker
 * lists from non-content columns, `raw` is read in the tenant's session ([OutboxSchema], INV-T-2997).
 * `submission-svc` and `reconciliation-svc`
 * do not exist yet; the fixtures play them.
 */
@Tag("integration")
class DeadLetterReplayIT {
    private val tenantA = UUID.randomUUID()
    private val tenantB = UUID.randomUUID()
    private val json = EnvelopeJson()

    private fun envelope(
        tenant: UUID,
        doc: UUID,
    ) = Envelope(
        id = EventId(UUID.randomUUID().toString()),
        source = "/gst/canonical-svc",
        type = TestCatalogue.CANONICAL_VERSION_CREATED,
        subject = doc.toString(),
        time = Instant.parse("2026-10-01T00:00:00Z"),
        tenant = TenantId(tenant),
        entity = EntityRef("entity-1"),
        docUuid = DocUuid(doc),
        transactionDate = LocalDate.of(2026, 9, 30),
        docSequence = 1,
        instanceId = "canonical-svc-1",
        correlation = CorrelationId("corr"),
        causation = CausationId("cause"),
        dataJson = """{"version":1}""",
    )

    private fun letter(
        harness: OutboxHarness,
        tenant: UUID?,
        raw: String,
        reason: String = "handler schema mismatch",
        consumer: String = "submission-svc",
    ): Long =
        // CS-19: tenant_id NOT NULL, a letter without a tenant is stored under the platform tenant
        JooqKit.recordDeadLetter(
            harness.fixture,
            "submission",
            consumer,
            "gst.canonical.canonical-version-created.v1",
            1,
            tenant ?: TenantId.PLATFORM.value,
            reason,
            raw,
        )

    private class Triage(
        harness: OutboxHarness,
        val clock: MutableClock = MutableClock(),
    ) {
        val kit = harness.submission
        val scope = kit.scope
        val triage = JdbcDeadLetterTriage(scope, kit.dsl, "submission", clock)

        fun delivery(
            harness: OutboxHarness,
            consumer: String,
            transport: TransportStub = TransportStub(),
        ): JdbcReplayDelivery {
            val inbox =
                JdbcInboxConsumer(
                    ConsumerSubscription(consumer, "gst.canonical.canonical-version-created.v1", "submission"),
                    harness.catalogue,
                    harness.reader,
                    scope,
                    kit.dsl,
                    SubmissionFixtureHandler(kit.boundary, transport),
                    harness.rebuildSink,
                )
            val limiter = LaneRateLimiter(scope, kit.dsl, "submission", LaneLimits())
            val queue = JdbcWorkQueue(scope, kit.dsl, "submission", limiter, clock, consumer)
            return JdbcReplayDelivery(queue, scope, inbox)
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `triage lists and filters open dead letters and discards one with a note`(image: String) {
        OutboxHarness(image).use { harness ->
            val docA = UUID.randomUUID()
            val a = letter(harness, tenantA, json.write(envelope(tenantA, docA)), reason = "schema: field x")
            val b = letter(harness, tenantB, "{not json", reason = "record is not JSON")
            val t = Triage(harness)

            assertEquals(listOf(a, b), t.triage.list().map { it.id })
            assertEquals(listOf(a), t.triage.list(DeadLetterScope(tenant = TenantId(tenantA))).map { it.id })
            assertEquals(listOf(a), t.triage.list(DeadLetterScope(failureClass = "schema")).map { it.id })
            assertEquals(listOf(a), t.triage.list(DeadLetterScope(subject = docA.toString())).map { it.id })
            assertEquals(listOf(a), t.triage.list(DeadLetterScope(entity = "entity-1")).map { it.id })
            assertEquals(listOf(b), t.triage.list(DeadLetterScope(ids = setOf(b))).map { it.id })
            assertEquals(DeadLetterStatus.OPEN, notNull(t.triage.get(a)).status)

            assertTrue(t.triage.discard(b, "producer bug, fixed upstream; events re-sent"))
            assertEquals(false, t.triage.discard(b, "again"))
            assertEquals(listOf(a), t.triage.list().map { it.id })
            assertEquals(
                DeadLetterStatus.DISCARDED,
                t.triage
                    .list(status = DeadLetterStatus.DISCARDED)
                    .single()
                    .status,
            )
            assertThrows(IllegalArgumentException::class.java) { t.triage.discard(a, " ") }
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `replay by scope reaches only the named consumer and only the dead letters in scope`(image: String) {
        OutboxHarness(image).use { harness ->
            val docA = UUID.randomUUID()
            val docB = UUID.randomUUID()
            val a = letter(harness, tenantA, json.write(envelope(tenantA, docA)))
            val inOtherScope = letter(harness, tenantA, json.write(envelope(tenantA, docB)))
            val noTenant = letter(harness, null, "{}")
            val t = Triage(harness)
            assertThrows(IllegalArgumentException::class.java) { t.triage.replay(DeadLetterScope(), "submission-svc") }

            val result = t.triage.replay(DeadLetterScope(subject = docA.toString()), "submission-svc")
            assertEquals(ReplayResult(queued = 1, skippedWithoutTenant = 0, alreadyQueued = 0), result)
            assertEquals(DeadLetterStatus.REPLAYED, notNull(t.triage.get(a)).status)
            assertEquals("submission-svc", notNull(t.triage.get(a)).replayedTo)
            assertEquals(DeadLetterStatus.OPEN, notNull(t.triage.get(inOtherScope)).status)

            // Another consumer receives nothing; the named one applies exactly the dead letter in scope.
            assertNull(t.delivery(harness, "reconciliation-svc").runOnce())
            val transport = TransportStub()
            assertEquals(ReplayOutcome.APPLIED, t.delivery(harness, "submission-svc", transport).runOnce())
            assertNull(t.delivery(harness, "submission-svc").runOnce())
            val registered = "select count(*) from submission.obligation_register where doc_uuid = ?"
            assertEquals(1, harness.countIn(tenantA, registered, docA))
            assertEquals(0, harness.countIn(tenantA, registered, docB))
            assertEquals(
                0,
                harness.countIn(tenantA, "select count(*) from submission.work_queue"),
                "the delivered item is gone",
            )
            assertEquals(
                1,
                harness.countIn(tenantA, "select count(*) from submission.inbox where consumer = 'submission-svc'"),
            )

            // A dead letter without a tenant cannot be replayed (no tenant to bind): reported, left open.
            val rest = t.triage.replay(DeadLetterScope(consumer = "submission-svc"), "submission-svc")
            assertEquals(ReplayResult(queued = 1, skippedWithoutTenant = 1, alreadyQueued = 0), rest)
            assertEquals(DeadLetterStatus.OPEN, notNull(t.triage.get(noTenant)).status)
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a replay run twice queues a dead letter once and an invalid envelope is dead-lettered again`(image: String) {
        OutboxHarness(image).use { harness ->
            val doc = UUID.randomUUID()
            val good = letter(harness, tenantA, json.write(envelope(tenantA, doc)))
            val incomplete = """{"specversion":"1.0","tenantid":"$tenantA","subject":"x"}"""
            val bad = letter(harness, tenantA, incomplete, reason = "missing fields")
            val t = Triage(harness)
            val scope = DeadLetterScope(ids = setOf(good, bad))
            assertEquals(2, t.triage.replay(scope, "submission-svc").queued)
            // The mark on the dead letter is lost (a crash between the two steps): the replay is repeated.
            // The runtime login holds SELECT only on the letters: the owner login resets them, once for tenantA.
            JooqKit.Access.owner(harness.fixture, OutboxSchema.jooqTables("submission")).use { owner ->
                owner.asTenant(tenantA) { dsl ->
                    dsl
                        .update(DSL.table(DSL.name("submission", OutboxSchema.DEAD_LETTER)))
                        .set(DSL.field(DSL.name("status"), SQLDataType.CLOB), "OPEN")
                        .setNull(DSL.field(DSL.name("resolved_at"), SQLDataType.TIMESTAMPWITHTIMEZONE))
                        .setNull(DSL.field(DSL.name("replayed_to"), SQLDataType.CLOB))
                        .execute()
                }
            }
            val again = t.triage.replay(scope, "submission-svc")
            assertEquals(ReplayResult(queued = 0, skippedWithoutTenant = 0, alreadyQueued = 2), again)
            assertEquals(2, harness.countIn(tenantA, "select count(*) from submission.work_queue"))

            val delivery = t.delivery(harness, "submission-svc")
            val outcomes = listOf(delivery.runOnce(), delivery.runOnce())
            assertEquals(setOf(ReplayOutcome.APPLIED, ReplayOutcome.REJECTED), outcomes.toSet())
            assertEquals(0, harness.countIn(tenantA, "select count(*) from submission.work_queue"))
            val reDeadLettered =
                "select count(*) from submission.event_dead_letter " +
                    "where status = 'OPEN' and raw like '%\"subject\":\"x\"%' and id <> ?"
            assertEquals(1, harness.countIn(tenantA, reDeadLettered, bad), "the rejected replay is dead-lettered again")
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `dead letters and queue items stay tenant data, reachable only by a tenant or the claim path`(image: String) {
        OutboxHarness(image).use { harness ->
            val doc = UUID.randomUUID()
            letter(harness, tenantA, json.write(envelope(tenantA, doc)))
            val t = Triage(harness)
            t.triage.replay(DeadLetterScope(tenant = TenantId(tenantA)), "submission-svc")
            val kit = harness.submission

            fun count(
                tenant: UUID,
                table: String,
            ) = kit.inTx(tenant) {
                kit.dsl
                    .selectCount()
                    .from(DSL.table(DSL.name("submission", table)))
                    .fetchSingle()
                    .value1()
            }
            assertEquals(listOf(true, true), harness.flagsOf("submission", "work_queue"))
            assertEquals(1, count(tenantA, "work_queue"))
            assertEquals(0, count(tenantB, "work_queue"), "another tenant sees no queue item")
            // A tenant session reads its own dead letters under the tenant policy and no other tenant's.
            assertEquals(1, count(tenantA, "event_dead_letter"))
            assertEquals(0, count(tenantB, "event_dead_letter"), "a cross-tenant raw read is refused")
            // Outside a tenant transaction the runtime login sees no queue item, cannot insert one and
            // updates none; only claim_due reaches across tenants (INV-Q-3953).
            val probe =
                TenancyProbe.connect(
                    harness.fixture.jdbcUrl(),
                    OutboxPostgresFixture.APP,
                    OutboxPostgresFixture.APP_PASSWORD,
                )
            assertEquals(0L, probe.unbound { fetchValue("select count(*) from submission.work_queue") })
            val refused =
                assertThrows(Exception::class.java) {
                    probe.unbound {
                        fetchRows(
                            "insert into submission.work_queue (tenant_id, partition_key, lane, payload) " +
                                "values (cast(? as uuid), 'k', 'LIVE', cast(? as jsonb)) returning 1",
                            tenantA.toString(),
                            "{}",
                        )
                    }
                }
            assertNotNull(JooqKit.sqlState(refused), "the insert is refused by the database")
            assertEquals(
                0,
                probe.unbound { fetchRows("update submission.work_queue set partition_key = 'x' returning 1") }.size,
            )
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `the runtime login reads no raw, a tenantless letter is triaged from metadata, raw filters read per tenant`(
        image: String,
    ) {
        OutboxHarness(image).use { harness ->
            val doc = UUID.randomUUID()
            val a = letter(harness, tenantA, json.write(envelope(tenantA, doc)))
            val b = letter(harness, tenantB, json.write(envelope(tenantB, doc)))
            val none = letter(harness, null, "{not json", reason = "record is not JSON")
            val t = Triage(harness)
            val probe =
                TenancyProbe.connect(
                    harness.fixture.jdbcUrl(),
                    OutboxPostgresFixture.APP,
                    OutboxPostgresFixture.APP_PASSWORD,
                )
            val view = "submission.${OutboxSchema.DEAD_LETTER_TRIAGE_VIEW}"
            assertEquals(0, probe.unbound { fetchRows("select raw from submission.event_dead_letter") }.size)
            listOf("select raw from $view", "select id from $view where raw is not null").forEach { sql ->
                val refused = assertThrows(Exception::class.java) { probe.unbound { fetchRows(sql) } }
                assertNotNull(JooqKit.sqlState(refused), "$sql is refused by the database")
            }
            val all = t.triage.list().associateBy { it.id }
            assertNull(all.getValue(none).raw, "a tenantless letter's raw is read by nobody")
            assertTrue(notNull(all.getValue(a).raw).contains(tenantA.toString()))
            assertTrue(notNull(all.getValue(b).raw).contains(tenantB.toString()))
            assertEquals(listOf(a, b), t.triage.list(DeadLetterScope(subject = doc.toString())).map { it.id })
            val ofA = DeadLetterScope(subject = doc.toString(), tenant = TenantId(tenantA))
            assertEquals(listOf(a), t.triage.list(ofA).map { it.id })
            assertEquals(emptyList<Long>(), t.triage.list(DeadLetterScope(entity = "nope")).map { it.id })
            assertNull(notNull(t.triage.get(none)).raw)
            assertTrue(t.triage.discard(none, "unparsable, producer fixed"))
        }
    }
}
