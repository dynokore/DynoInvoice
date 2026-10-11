package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.SQLException
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
    ): Long = harness.fixture.adminDataSource().connection.use { c ->
        c
            .prepareStatement(
                "insert into submission.event_dead_letter (consumer, topic, log_offset, tenant_id, reason, raw) " +
                    "values (?, 'gst.canonical.canonical-version-created.v1', 1, ?, ?, ?) returning id",
            ).use {
                it.setString(1, consumer)
                it.setObject(2, tenant)
                it.setString(3, reason)
                it.setString(4, raw)
                it.executeQuery().use { rows ->
                    rows.next()
                    rows.getLong(1)
                }
            }
    }

    private class Triage(
        harness: OutboxHarness,
        val clock: MutableClock = MutableClock(),
    ) {
        val kit = harness.submission
        val scope = TenantScope(kit.tenants, kit.boundary, kit.boundary)
        val triage = JdbcDeadLetterTriage(harness.fixture.workerDataSource(), scope, "submission", clock)

        fun delivery(
            harness: OutboxHarness,
            consumer: String,
            transport: TransportStub = TransportStub(),
        ): JdbcReplayDelivery {
            val inbox =
                JdbcInboxConsumer(
                    ConsumerSubscription(consumer, "gst.canonical.canonical-version-created.v1", "submission"),
                    harness.reader,
                    harness.fixture.workerDataSource(),
                    scope,
                    SubmissionFixtureHandler(kit.boundary, transport),
                )
            val limiter = LaneRateLimiter(harness.fixture.workerDataSource(), "submission", LaneLimits())
            val queue = JdbcWorkQueue(harness.fixture.workerDataSource(), scope, "submission", limiter, clock, consumer)
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
            assertEquals(DeadLetterStatus.DISCARDED, t.triage.list(status = DeadLetterStatus.DISCARDED).single().status)
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
            assertEquals(1, harness.count(registered, docA))
            assertEquals(0, harness.count(registered, docB))
            assertEquals(0, harness.count("select count(*) from submission.work_queue"), "the delivered item is gone")
            assertEquals(1, harness.count("select count(*) from submission.inbox where consumer = 'submission-svc'"))

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
            harness.fixture.runAsAdmin(
                "update submission.event_dead_letter set status = 'OPEN', resolved_at = null, replayed_to = null",
            )
            val again = t.triage.replay(scope, "submission-svc")
            assertEquals(ReplayResult(queued = 0, skippedWithoutTenant = 0, alreadyQueued = 2), again)
            assertEquals(2, harness.count("select count(*) from submission.work_queue"))

            val delivery = t.delivery(harness, "submission-svc")
            val outcomes = listOf(delivery.runOnce(), delivery.runOnce())
            assertEquals(setOf(ReplayOutcome.APPLIED, ReplayOutcome.REJECTED), outcomes.toSet())
            assertEquals(0, harness.count("select count(*) from submission.work_queue"))
            val reDeadLettered =
                "select count(*) from submission.event_dead_letter " +
                    "where status = 'OPEN' and raw like '%\"subject\":\"x\"%' and id <> ?"
            assertEquals(1, harness.count(reDeadLettered, bad), "the rejected replay is dead-lettered again")
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
            fun count(tenant: UUID, table: String) = kit.inTx(tenant) {
                kit.boundary.require().createStatement().use { s ->
                    s.executeQuery("select count(*) from submission.$table").use {
                        it.next()
                        it.getInt(1)
                    }
                }
            }
            assertEquals(listOf(true, true), harness.flags("submission", "work_queue"))
            assertEquals(1, count(tenantA, "work_queue"))
            assertEquals(0, count(tenantB, "work_queue"), "another tenant sees no queue item")
            // A tenant session reads its own dead letters under the tenant policy and no other tenant's.
            assertEquals(1, count(tenantA, "event_dead_letter"))
            assertEquals(0, count(tenantB, "event_dead_letter"), "a cross-tenant raw read is refused")
            harness.fixture.workerDataSource().connection.use { c ->
                c.createStatement().use { s ->
                    // The worker claims across tenants but sees no payload and cannot insert queue items.
                    s.executeQuery("select count(*) from submission.work_queue").use {
                        it.next()
                        assertEquals(1, it.getInt(1))
                    }
                    assertThrows(SQLException::class.java) {
                        s.executeQuery("select payload from submission.work_queue")
                    }
                    assertThrows(SQLException::class.java) {
                        s.executeUpdate(
                            "insert into submission.work_queue (tenant_id, partition_key, lane, payload) " +
                                "values ('$tenantA', 'k', 'LIVE', '{}')",
                        )
                    }
                    assertThrows(SQLException::class.java) {
                        s.executeUpdate("update submission.work_queue set partition_key = 'x'")
                    }
                }
            }
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `the worker cannot select raw, a tenantless letter is triaged from metadata, and raw filters read per tenant`(
        image: String,
    ) {
        OutboxHarness(image).use { harness ->
            val doc = UUID.randomUUID()
            val a = letter(harness, tenantA, json.write(envelope(tenantA, doc)))
            val b = letter(harness, tenantB, json.write(envelope(tenantB, doc)))
            val none = letter(harness, null, "{not json", reason = "record is not JSON")
            val t = Triage(harness)
            harness.fixture.workerDataSource().connection.use { c ->
                c.createStatement().use { s ->
                    val table = "submission.event_dead_letter"
                    assertThrows(SQLException::class.java) { s.executeQuery("select raw from $table") }
                    assertThrows(SQLException::class.java) { s.executeQuery("select * from $table") }
                    assertThrows(SQLException::class.java) {
                        s.executeQuery("select id from submission.event_dead_letter where raw is not null")
                    }
                }
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
