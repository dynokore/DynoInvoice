package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TenantRlsMigration
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Rebuilding a consumer behind the purge watermark by the producers' replay (Keel INV-T-3385; INV-REQ-1205 b3, b4, b6;
 * INV-REQ-1372; rulings INV-Q-3599, INV-Q-3601, INV-Q-3603, INV-Q-3625, INV-Q-3644, INV-Q-3669, INV-Q-3671, INV-Q-3676;
 * INV-AC-3657, INV-AC-3658), on PostgreSQL 17 and 18.
 *
 * `canonical-svc` produces CanonicalVersionCreated from its own state table and serves a [ReplayStateSource] over it;
 * `reconciliation-svc` keeps a version read model. A control consumer reads everything before the purge; the consumer
 * under test falls behind the watermark, asks for a replay through its request topic, keeps applying live events
 * while the replay arrives on its own replay topic, and ends with the control's read model.
 */
@Tag("integration")
class ProducerReplayIT {
    private val live = "gst.canonical.canonical-version-created.v1"
    private val liveTable = "eventlog.gst_canonical_canonical_version_created_v1"
    private val replayTopic = "gst.canonical.replay-reconciliation.v1"
    private val submissionReplay = "eventlog.gst_canonical_replay_submission_v1"
    private val consumer = "reconciliation-svc"
    private val control = "recon-control"
    private val tenantA = TenantId(UUID.randomUUID())
    private val tenantB = TenantId(UUID.randomUUID())
    private val clock = Clock.systemUTC()

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a consumer behind the watermark is rebuilt by a replay by scope while live events go on`(image: String) {
        Scenario(image).use { s ->
            val d1 = UUID.randomUUID()
            val d2 = UUID.randomUUID()
            val d3 = UUID.randomUUID()
            s.change(tenantA, d1, 1)
            s.change(tenantA, d1, 2)
            s.change(tenantA, d2, 1)
            s.change(tenantB, d3, 1)
            s.publishCanonical()
            assertEquals(4, s.controlConsumer.pollOnce().applied)

            s.purgeLive()
            assertTrue(s.behindConsumer.pollOnce().rebuildRequired)
            assertEquals(listOf(consumer to live), s.harness.rebuildIncidents.map { it.consumer to it.topic })

            // The whole history, across tenants: a PERIOD scope on the time the state last changed (INV-Q-3671).
            val replayId = s.request(ReplayScope.Period(Instant.EPOCH, Instant.now().plus(Duration.ofDays(36_500))))
            s.publishReconciliation()

            // Live events after the head move come from the log while the replay is still to come.
            s.change(tenantA, d2, 2)
            s.publishCanonical()
            assertEquals(1, s.behindConsumer.pollOnce().applied, "the live event after the head move is applied")
            assertEquals(1, s.controlConsumer.pollOnce().applied)
            assertTrue(s.harness.closed.isEmpty(), "the incident stays open until the replay completes")

            val answers = s.responder().runOnce()
            assertEquals(listOf(ReplayAnswer(replayId, consumer, replayTopic, 3)), answers)
            s.publishCanonical()
            val intake = s.intake.pollOnce()
            assertEquals(2, intake.applied - 1, "d1 v2 and d3 v1 applied, plus the marker")
            assertEquals(1, intake.stale, "d2 v2 was already applied live: STALE")

            assertEquals(s.view(control), s.view(consumer), "the same read model as a consumer that never fell behind")
            assertEquals(listOf(consumer to live), s.harness.closed.map { it.consumer to it.topic })
            assertEquals(
                replayId,
                s.harness.closed
                    .single()
                    .replayId,
            )
            assertEquals(0, s.intake.pollOnce().total)
            assertEquals(1, s.harness.closed.size, "closed once")
            assertEquals(0, s.behindConsumer.pollOnce().total)
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a tenant or entity scope replays only that scope, and an empty scope still completes`(image: String) {
        Scenario(image).use { s ->
            val d1 = UUID.randomUUID()
            val d2 = UUID.randomUUID()
            s.change(tenantA, d1, 1)
            s.change(tenantB, d2, 1)
            s.publishCanonical()
            s.controlConsumer.pollOnce()
            s.purgeLive()
            assertTrue(s.behindConsumer.pollOnce().rebuildRequired)

            s.request(ReplayScope.Entity(tenantA, EntityRef("entity-$d1")))
            s.publishReconciliation()
            assertEquals(
                1L,
                s
                    .responder()
                    .runOnce()
                    .single()
                    .eventsReplayed,
            )
            s.publishCanonical()
            s.intake.pollOnce()
            assertEquals(mapOf(d1 to 1), s.view(consumer), "only the entity's state")
            assertEquals(1, s.harness.closed.size)

            assertEmptyTenantReplayCompletes(s)
        }
    }

    /** A second rebuild: an empty tenant scope replays the marker alone and closes its incident. */
    private fun assertEmptyTenantReplayCompletes(s: Scenario) {
        // A second rebuild after the first completed: a tenant with no state gives a replay of the marker alone.
        s.purgeLive(alreadyBehind = false)
        assertTrue(s.behindConsumer.pollOnce().rebuildRequired)
        s.request(ReplayScope.Tenant(TenantId(UUID.randomUUID())))
        s.publishReconciliation()
        assertEquals(
            0L,
            s
                .responder()
                .runOnce()
                .single()
                .eventsReplayed,
        )
        s.publishCanonical()
        assertEquals(1, s.intake.pollOnce().applied, "the marker alone")
        assertEquals(2, s.harness.closed.size, "the empty replay closes its incident too (INV-AC-3658)")
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a consumer never receives another's replay, and its roles cannot read that pair's topic`(image: String) {
        Scenario(image).use { s ->
            val d1 = UUID.randomUUID()
            s.change(tenantA, d1, 1)
            s.publishCanonical()
            s.purgeLive()

            // submission-svc asks canonical-svc for a replay; reconciliation-svc asked for nothing.
            val sub = s.harness.submission
            val request = replayRequest(ReplayScope.Tenant(tenantA))
            sub.inPlatformTx {
                JdbcConsumerReplay("submission", sub.boundary, s.harness.reader, sub.writer, clock)
                    .request("submission-svc", listOf(live), request, corr(), cause())
            }
            s.harness.drain(s.harness.submission.relay)
            val answer = s.responder().runOnce().single()
            assertEquals("gst.canonical.replay-submission.v1", answer.replayTopic)
            s.publishCanonical()

            assertEquals(0, s.intake.pollOnce().total, "nothing on reconciliation-svc's own replay topic")
            val type = TestCatalogue.CANONICAL_VERSION_CREATED
            val replayed = "select count(*) from $submissionReplay where envelope->>'type' = '$type'"
            assertEquals(1L, s.harness.count(replayed))
            s.harness.fixture.roleDataSource("recon_runtime").connection.use { connection ->
                assertThrows(SQLException::class.java) {
                    connection.createStatement().executeQuery("select log_offset from $submissionReplay")
                }
            }
            // ...while submission-svc's own login can.
            s.harness.fixture.roleDataSource("sub_runtime").connection.use { connection ->
                connection.createStatement().executeQuery("select count(log_offset) from $submissionReplay").use {
                    assertTrue(it.next())
                }
            }
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `the head move and the request commit together, a failure between them leaves neither`(image: String) {
        Scenario(image).use { s ->
            s.change(tenantA, UUID.randomUUID(), 1)
            s.publishCanonical()
            s.purgeLive()
            assertTrue(s.behindConsumer.pollOnce().rebuildRequired)
            val kit = s.harness.reconciliation
            val replay = JdbcConsumerReplay("reconciliation", kit.boundary, s.harness.reader, kit.writer, clock)

            assertThrows(IllegalStateException::class.java) {
                kit.inPlatformTx {
                    replay.request(consumer, listOf(live), replayRequest(ReplayScope.Tenant(tenantA)), corr(), cause())
                    error("the process fails before the commit")
                }
            }
            // A writer whose catalogue has no request topic for the service fails after the head move: nothing stays.
            val withoutRequests = EventCatalogue(s.harness.catalogue.entries())
            val noRequestTopic =
                JdbcOutboxWriter(kit.boundary, "reconciliation", consumer, "r-1", withoutRequests, clock)
            assertThrows(EmissionNotPermitted::class.java) {
                kit.inPlatformTx {
                    JdbcConsumerReplay("reconciliation", kit.boundary, s.harness.reader, noRequestTopic, clock)
                        .request(consumer, listOf(live), replayRequest(ReplayScope.Tenant(tenantA)), corr(), cause())
                }
            }
            assertNull(s.harness.nextOffsetOrNull("reconciliation", consumer, live), "no head move committed")
            assertEquals(0L, s.harness.count("select count(*) from reconciliation.outbox"))
            assertEquals(0L, s.harness.count("select count(*) from reconciliation.consumer_replay"))
            assertTrue(s.behindConsumer.pollOnce().rebuildRequired, "still in rebuild required")

            s.request(ReplayScope.Tenant(tenantA))
            val requested = ReplayProtocol.REPLAY_REQUESTED
            assertEquals(1L, s.harness.count("select count(*) from reconciliation.outbox where type = '$requested'"))
            val pending = "select count(*) from reconciliation.consumer_replay where completed_at is null"
            assertEquals(1L, s.harness.count(pending))
            assertEquals(2L, s.offset(), "moved past the head (offset 1)")
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a replay interrupted by a producer outage after the request restarts and completes once`(image: String) {
        Scenario(image).use { s ->
            val docs = (1..3).map { UUID.randomUUID() }
            docs.forEach { s.change(tenantA, it, 1) }
            s.publishCanonical()
            s.controlConsumer.pollOnce()
            s.purgeLive()
            assertTrue(s.behindConsumer.pollOnce().rebuildRequired)
            val replayId = s.request(ReplayScope.Tenant(tenantA))
            s.publishReconciliation()

            // The producer goes down after the request: the first run dies after one page, before the marker.
            val failing = s.responder(pageSize = 1, failAfterPages = 1)
            assertThrows(IllegalStateException::class.java) { failing.runOnce() }
            s.publishCanonical()
            assertEquals(1, s.intake.pollOnce().applied, "the first page arrived")
            assertTrue(s.harness.closed.isEmpty(), "no marker yet")

            // Back up: it reads the request again and restarts the replay under the same replay id.
            val answer = s.responder(pageSize = 1).runOnce().single()
            assertEquals(replayId, answer.replayId)
            assertEquals(3L, answer.eventsReplayed)
            s.publishCanonical()
            val intake = s.intake.pollOnce()
            assertEquals(1, intake.stale, "the re-sent first state is STALE")
            assertEquals(3, intake.applied, "the other two states and the marker")
            assertEquals(s.view(control), s.view(consumer))
            assertEquals(1, s.harness.closed.size)
            assertEquals(emptyList<ReplayAnswer>(), s.responder().runOnce(), "the request is handled once")
            val markers =
                "select count(*) from eventlog.gst_canonical_replay_reconciliation_v1 " +
                    "where envelope->>'type' = '${ReplayProtocol.REPLAY_COMPLETED}'"
            assertEquals(1L, s.harness.count(markers))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `an 'every service' replay completes when a marker from each source seen has been applied`(image: String) {
        OutboxHarness(image).use { harness ->
            val audit = "gst.platform.audit-entry-recorded.v1"
            val evidence = harness.evidence
            val subscription = ConsumerSubscription("evidence-svc", audit, "evidence", trackSources = true)
            val live = evidenceConsumer(harness, subscription)
            // evidence-svc has seen audit entries from payload-svc and submission-svc.
            for (kit in listOf(harness.payload, harness.submission)) {
                kit.inTx(ServiceKit.TENANT) {
                    val event = OutboundEvent(TestCatalogue.AUDIT_ENTRY_RECORDED, "{}")
                    kit.writer.append(listOf(event), kit.docContext(ServiceKit.TENANT, UUID.randomUUID()))
                }
                harness.drain(kit.relay)
            }
            assertEquals(2, live.pollOnce().applied)
            val replayId = UUID.randomUUID()
            evidence.inPlatformTx {
                JdbcConsumerReplay("evidence", evidence.boundary, harness.reader, evidence.writer, clock)
                    .request("evidence-svc", listOf(audit), everyService(replayId), corr(), cause())
            }
            harness.drain(evidence.relay)
            val intake = evidenceIntake(harness)
            answerEmpty(harness, harness.payload, "payload")
            answerEmpty(harness, harness.canonical, "canonical") // a service never seen: its marker changes nothing
            assertEquals(2, intake.pollOnce().applied)
            assertTrue(harness.closed.isEmpty(), "submission-svc has not answered yet")
            answerEmpty(harness, harness.submission, "submission")
            assertEquals(1, intake.pollOnce().applied)
            assertEquals(listOf(replayId), harness.closed.map { it.replayId })
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `a full sync clears two tenants' rows in one runtime-login transaction and rebuilds the read model`(
        image: String,
    ) {
        Scenario(image).use { s ->
            val a1 = UUID.randomUUID()
            val b1 = UUID.randomUUID()
            s.change(tenantA, a1, 1)
            s.change(tenantA, a1, 2)
            s.change(tenantB, b1, 1)
            s.publishCanonical()
            assertEquals(3, s.behindConsumer.pollOnce().applied, "the consumer holds sequences in both tenants")
            s.change(tenantB, UUID.randomUUID(), 1)
            s.publishCanonical()
            assertEquals(4, s.controlConsumer.pollOnce().applied)
            s.purgeLive()
            assertTrue(s.behindConsumer.pollOnce().rebuildRequired)
            val sequences = "select count(*) from reconciliation.consumer_sequence where consumer = '$consumer'"
            assertEquals(2L, s.harness.count(sequences))
            val inboxRows = "select count(*) from reconciliation.inbox where consumer = '$consumer'"
            val inbox = s.harness.count(inboxRows)
            assertRolesStayInTheirTenant(s)

            val replayId = s.fullSync()
            assertEquals(0L, s.harness.count(sequences), "both tenants' docsequence rows deleted")
            val viewRows = "select count(*) from reconciliation.version_view where consumer = '$consumer'"
            assertEquals(0L, s.harness.count(viewRows))
            assertEquals(inbox, s.harness.count(inboxRows), "inbox kept")
            assertEquals(5L, s.offset(), "past the head and the watermark (4)")
            s.publishReconciliation()

            val answer = s.responder().runOnce().single()
            assertEquals(replayId, answer.replayId)
            assertEquals(3L, answer.eventsReplayed, "every tenant's current state")
            s.publishCanonical()
            assertEquals(4, s.intake.pollOnce().applied, "three states and the marker, none STALE")
            assertEquals(s.view(control), s.view(consumer), "the same read model as a consumer that never fell behind")
            assertEquals(listOf(replayId), s.harness.closed.map { it.replayId })
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `INV-Q-3805 an 'every service' full sync deletes no docsequence rows`(image: String) {
        Scenario(image).use { s ->
            s.change(tenantA, UUID.randomUUID(), 1)
            s.change(tenantB, UUID.randomUUID(), 1)
            s.publishCanonical()
            assertEquals(2, s.behindConsumer.pollOnce().applied)
            val sequences = "select count(*) from reconciliation.consumer_sequence where consumer = '$consumer'"
            assertEquals(2L, s.harness.count(sequences))
            val request = ReplayRequest(UUID.randomUUID(), CatalogueEntry.EVERY_SERVICE, ReplayMode.FULL_SYNC, null)
            s.syncTenants.bindPlatform("svc:test/job:probe") {
                s.syncTx.inTransaction {
                    JdbcConsumerReplay("reconciliation", s.syncTx, s.harness.reader, s.syncWriter, clock)
                        .fullSync(consumer, listOf(live), request, null, corr(), cause())
                }
            }
            assertEquals(2L, s.harness.count(sequences), "every docsequence row survives")
            assertEquals(1L, s.harness.count("select count(*) from reconciliation.consumer_replay"))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `a full sync failing before its commit leaves read model, sequences and position as they were`(image: String) {
        Scenario(image).use { s ->
            s.change(tenantA, UUID.randomUUID(), 1)
            s.change(tenantB, UUID.randomUUID(), 1)
            s.publishCanonical()
            s.behindConsumer.pollOnce()
            s.purgeLive(alreadyBehind = false)
            val before = s.view(consumer)
            assertEquals(2, before.size)
            // A writer without the service's request topic fails after the clear, the delete and the head move.
            val withoutRequests = EventCatalogue(s.harness.catalogue.entries())
            val noRequestTopic =
                JdbcOutboxWriter(s.syncTx, "reconciliation", consumer, "$consumer-w", withoutRequests, clock)
            val clear = s.readModelClear()
            assertThrows(EmissionNotPermitted::class.java) {
                s.syncTenants.bindPlatform("svc:test/job:probe") {
                    s.syncTx.inTransaction {
                        JdbcConsumerReplay("reconciliation", s.syncTx, s.harness.reader, noRequestTopic, clock)
                            .fullSync(
                                consumer,
                                listOf(live),
                                ReplayRequest(UUID.randomUUID(), "canonical-svc", ReplayMode.FULL_SYNC, null),
                                clear,
                                corr(),
                                cause(),
                            )
                    }
                }
            }
            assertEquals(before, s.view(consumer))
            val sequences = "select count(*) from reconciliation.consumer_sequence where consumer = '$consumer'"
            assertEquals(2L, s.harness.count(sequences))
            assertEquals(3L, s.offset())
        }
    }

    /** The runtime login cannot delete docsequence rows or see another tenant's, bound or not. */
    private fun assertRolesStayInTheirTenant(s: Scenario) {
        val b = tenantB.value
        // The app role cannot delete docsequence rows at all, and bound to one tenant it sees no other tenant's.
        val recon = s.harness.reconciliation
        assertThrows(SQLException::class.java) {
            recon.inTx(tenantA.value) {
                recon.boundary.require().createStatement().use {
                    it.executeUpdate("delete from reconciliation.consumer_sequence where tenant_id = '$b'")
                }
            }
        }
        recon.inTx(tenantA.value) {
            val seen =
                recon.boundary.require().createStatement().use {
                    it
                        .executeQuery("select count(*) from reconciliation.version_view where tenant_id = '$b'")
                        .use { rows ->
                            rows.next()
                            rows.getLong(1)
                        }
                }
            assertEquals(0L, seen)
        }
        // The runtime login, bound to no tenant, sees and deletes no tenant's read-model or docsequence rows directly.
        s.harness.fixture.runtimeDataSource().connection.use { connection ->
            connection.createStatement().use {
                assertEquals(0, it.executeUpdate("delete from reconciliation.version_view"))
                it.executeQuery("select count(*) from reconciliation.consumer_sequence").use { rows ->
                    rows.next()
                    assertEquals(0L, rows.getLong(1))
                }
            }
        }
    }

    private fun replayRequest(scope: ReplayScope): ReplayRequest {
        val id = UUID.randomUUID()
        return ReplayRequest(id, "canonical-svc", ReplayMode.SCOPE, scope)
    }

    private fun everyService(replayId: UUID): ReplayRequest {
        val scope = ReplayScope.Tenant(tenantA)
        return ReplayRequest(replayId, CatalogueEntry.EVERY_SERVICE, ReplayMode.SCOPE, scope)
    }

    private fun corr() = CorrelationId("test-${UUID.randomUUID()}")

    private fun cause() = CausationId("op-${UUID.randomUUID()}")

    /** The canonical producer, the reconciliation consumers and their read models over one harness. */
    private inner class Scenario(
        image: String,
    ) : AutoCloseable {
        val harness = OutboxHarness(image)
        private val canonical = harness.canonical
        private val recon = harness.reconciliation

        init {
            harness.fixture.run(
                """
                create table canonical.doc_state (
                    tenant_id uuid not null, doc_uuid uuid primary key, version int not null,
                        changed_at timestamptz not null);
                ${tenantRls("canonical", "doc_state")}
                grant select, insert, update on canonical.doc_state to ${OutboxPostgresFixture.APP};
                create table reconciliation.version_view (
                    consumer text not null, tenant_id uuid not null, doc_uuid uuid not null, version int not null,
                    primary key (consumer, doc_uuid));
                ${tenantRls("reconciliation", "version_view")}
                grant select, insert, update, delete on reconciliation.version_view to ${OutboxPostgresFixture.APP};
                """.trimIndent(),
            )
            harness.fixture.run(
                OutboxSchema.readModelClearFunction(
                    "reconciliation",
                    "version_view",
                    OutboxPostgresFixture.APP,
                    OutboxPostgresFixture.SYS,
                    "consumer",
                ),
            )
        }

        /** reconciliation-svc's runtime login: its full sync runs here, bound to the platform tenant. */
        val syncTenants = TenantContextImpl()
        val syncTx = TransactionRunnerImpl(harness.fixture.runtimeDataSource(), syncTenants)
        val syncWriter =
            JdbcOutboxWriter(syncTx, "reconciliation", consumer, "$consumer-w", harness.catalogue, clock)

        fun readModelClear(): ReadModelClear {
            val table = ReadModelTable("version_view", consumer)
            return ReadModelClear("canonical-svc", listOf(table))
        }

        fun offset() = harness.nextOffsetOrNull("reconciliation", consumer, live)

        // rebuildConsumer's full sync: clear, docsequence delete, head move, request; one runtime transaction.
        fun fullSync(): UUID {
            val request = ReplayRequest(UUID.randomUUID(), "canonical-svc", ReplayMode.FULL_SYNC, null)
            val clear = readModelClear()
            syncTenants.bindPlatform("svc:test/job:probe") {
                syncTx.inTransaction {
                    JdbcConsumerReplay("reconciliation", syncTx, harness.reader, syncWriter, clock)
                        .fullSync(consumer, listOf(live), request, clear, corr(), cause())
                }
            }
            return request.replayId
        }

        val controlConsumer = harness.consumer(control, live, "reconciliation", recon.boundary, ViewHandler(control))
        val behindConsumer = harness.consumer(consumer, live, "reconciliation", recon.boundary, ViewHandler(consumer))
        val intake =
            JdbcReplayIntake(
                ConsumerSubscription(consumer, replayTopic, "reconciliation"),
                harness.catalogue,
                "canonical-svc",
                harness.readerFor(harness.fixture.replayLogin(consumer)),
                harness.fixture.runtimeDataSource(),
                recon.scope,
                ViewHandler(consumer),
                harness.rebuildSink,
            )

        /** canonical-svc's command: the state change and its event in one transaction, docsequence = version. */
        fun change(
            tenant: TenantId,
            doc: UUID,
            version: Int,
        ) {
            canonical.inTx(tenant.value) {
                canonical.boundary
                    .require()
                    .prepareStatement(
                        "insert into canonical.doc_state values (?, ?, ?, now()) " +
                            "on conflict (doc_uuid) do update set version = excluded.version, changed_at = now()",
                    ).use {
                        it.setObject(1, tenant.value)
                        it.setObject(2, doc)
                        it.setInt(3, version)
                        it.executeUpdate()
                    }
                canonical.writer.append(
                    listOf(OutboundEvent(TestCatalogue.CANONICAL_VERSION_CREATED, """{"version":$version}""")),
                    context(tenant, doc, version),
                )
            }
        }

        fun publishCanonical() = harness.drain(canonical.relay)

        fun publishReconciliation() = harness.drain(recon.relay)

        /** Purges every row of the live topic and raises its watermark, as the event-log purge does. */
        fun purgeLive(alreadyBehind: Boolean = true) {
            if (!alreadyBehind) {
                // New history the consumer has not read: one more change, published, then purged.
                change(tenantA, UUID.randomUUID(), 1)
                publishCanonical()
            }
            harness.fixture.runAsAdmin(
                "insert into eventlog.purge_watermark (topic_key, watermark) " +
                    "select '${EventLogSchema.tableName(live)}', max(log_offset) from $liveTable " +
                    "on conflict (topic_key) do update set watermark = excluded.watermark; delete from $liveTable",
            )
        }

        /** reconciliation-svc's rebuild step: head move and ReplayRequested in one platform transaction. */
        fun request(scope: ReplayScope): UUID {
            val replay = JdbcConsumerReplay("reconciliation", recon.boundary, harness.reader, recon.writer, clock)
            val request = replayRequest(scope)
            recon.inPlatformTx { replay.request(consumer, listOf(live), request, corr(), cause()) }
            return request.replayId
        }

        fun responder(
            pageSize: Int = 200,
            failAfterPages: Int? = null,
        ) = JdbcReplayResponder(
            "canonical-svc",
            "canonical",
            harness.catalogue,
            harness.reader,
            harness.fixture.runtimeDataSource(),
            canonical.scope,
            canonical.writer,
            listOf(CanonicalStates(failAfterPages)),
            harness.rebuildSink,
            clock,
            pageSize,
        )

        /** doc → version of [name]'s read model. */
        fun view(name: String): Map<UUID, Int> =
            harness.fixture.adminDataSource().connection.use { connection ->
                val sql = "select doc_uuid, version from reconciliation.version_view where consumer = ?"
                connection.prepareStatement(sql).use {
                    it.setString(1, name)
                    it.executeQuery().use { rows ->
                        buildMap { while (rows.next()) put(rows.getObject(1, UUID::class.java), rows.getInt(2)) }
                    }
                }
            }

        override fun close() = harness.close()

        private fun context(
            tenant: TenantId,
            doc: UUID,
            version: Int,
        ) = EventContext(
            tenant = tenant,
            entity = EntityRef("entity-$doc"),
            docUuid = DocUuid(doc),
            transactionDate = LocalDate.of(2026, 9, 30),
            aggregateKey = doc.toString(),
            aggregateVersion = version.toLong(),
            correlation = CorrelationId("corr-$doc"),
            causation = CausationId("cmd-$doc-$version"),
        )

        /** canonical-svc's state source over doc_state (one state per document, its version as docsequence). */
        private inner class CanonicalStates(
            private val failAfterPages: Int?,
        ) : ReplayStateSource {
            private var pages = 0
            override val eventType = TestCatalogue.CANONICAL_VERSION_CREATED

            override fun tenants(): List<TenantId> =
                harness.fixture.adminDataSource().connection.use { connection ->
                    val sql = "select distinct tenant_id from canonical.doc_state order by 1"
                    connection.createStatement().executeQuery(sql).use { rows ->
                        buildList { while (rows.next()) add(TenantId(rows.getObject(1, UUID::class.java))) }
                    }
                }

            override fun page(
                scope: ReplayScope?,
                cursor: String?,
                limit: Int,
            ): ReplayPage {
                if (failAfterPages != null && pages++ >= failAfterPages) error("canonical-svc went down mid-replay")
                val filter =
                    when (scope) {
                        is ReplayScope.Entity -> " and 'entity-' || doc_uuid::text = '${scope.entity.value}'"
                        is ReplayScope.Period -> " and changed_at >= '${scope.from}' and changed_at < '${scope.to}'"
                        else -> ""
                    }
                val states =
                    canonical.boundary
                        .require()
                        .prepareStatement(
                            "select doc_uuid, version from canonical.doc_state " +
                                "where doc_uuid::text > ?$filter order by doc_uuid limit ?",
                        ).use {
                            it.setString(1, cursor ?: "")
                            it.setInt(2, limit)
                            it.executeQuery().use { rows ->
                                buildList {
                                    while (rows.next()) {
                                        val doc = rows.getObject(1, UUID::class.java)
                                        val version = rows.getInt(2)
                                        add(
                                            ReplayedState(
                                                eventType,
                                                """{"version":$version}""",
                                                EntityRef("entity-$doc"),
                                                doc.toString(),
                                                version.toLong(),
                                                DocUuid(doc),
                                                LocalDate.of(2026, 9, 30),
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                return ReplayPage(states, if (states.size == limit) states.last().aggregateKey else null)
            }
        }

        /** reconciliation-svc's read model: the latest version per document, per consumer name. */
        private inner class ViewHandler(
            private val name: String,
        ) : EventHandler {
            override fun handle(event: Envelope): HandleOutcome {
                val version =
                    harness.mapper
                        .readTree(event.dataJson)
                        .get("version")
                        .asInt()
                recon.boundary
                    .require()
                    .prepareStatement(
                        "insert into reconciliation.version_view values (?, ?, ?, ?) " +
                            "on conflict (consumer, doc_uuid) do update set version = excluded.version",
                    ).use {
                        it.setString(1, name)
                        it.setObject(2, event.tenant.value)
                        it.setObject(3, UUID.fromString(event.subject))
                        it.setInt(4, version)
                        it.executeUpdate()
                    }
                return HandleOutcome.APPLIED
            }
        }
    }

    private fun tenantRls(
        schema: String,
        table: String,
    ) = TenantRlsMigration.ddl(schema, table, policyName = "${table}_tenant_isolation")
}

private fun evidenceConsumer(
    harness: OutboxHarness,
    subscription: ConsumerSubscription,
) = JdbcInboxConsumer(
    subscription,
    harness.catalogue,
    harness.reader,
    harness.fixture.runtimeDataSource(),
    harness.evidence.scope,
    countingHandler(),
    harness.rebuildSink,
)

private fun evidenceIntake(harness: OutboxHarness) =
    JdbcReplayIntake(
        ConsumerSubscription("evidence-svc", "gst.platform.replay-evidence.v1", "evidence"),
        harness.catalogue,
        CatalogueEntry.EVERY_SERVICE,
        harness.readerFor(harness.fixture.replayLogin("evidence-svc")),
        harness.fixture.runtimeDataSource(),
        harness.evidence.scope,
        countingHandler(),
        harness.rebuildSink,
    )

/** [kit]'s responder answers the pending 'every service' request with no state: the marker alone. */
private fun answerEmpty(
    harness: OutboxHarness,
    kit: ServiceKit,
    schema: String,
) {
    val responder =
        JdbcReplayResponder(
            kit.service,
            schema,
            harness.catalogue,
            harness.reader,
            harness.fixture.runtimeDataSource(),
            kit.scope,
            kit.writer,
            emptyList(),
            harness.rebuildSink,
        )
    assertEquals(listOf(0L), responder.runOnce().map { it.eventsReplayed })
    harness.drain(kit.relay)
}

private fun countingHandler() =
    object : EventHandler {
        override fun handle(event: Envelope) = HandleOutcome.APPLIED
    }
