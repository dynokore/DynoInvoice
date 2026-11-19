package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.http.EntitiesController
import com.dynokore.gst.tenancy.adapters.http.TenantsController
import com.dynokore.gst.tenancy.contract.model.CreateEntityRequest
import com.dynokore.gst.tenancy.contract.model.CreateGroupRequest
import com.dynokore.gst.tenancy.contract.model.CreateTenantRequest
import com.dynokore.gst.tenancy.contract.model.SetEntityStatusRequest
import com.dynokore.gst.tenancy.domain.TenantRefs
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EnvelopeJson
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.InboxConsumer
import com.dynokore.platform.outboxandqueue.LogRecord
import com.dynokore.platform.outboxandqueue.ReplayJson
import com.dynokore.platform.outboxandqueue.ReplayMode
import com.dynokore.platform.outboxandqueue.ReplayRequest
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * INV-AC-25010 end to end: a ReplayRequested record on the request topic of `access-svc` goes through the driver bean
 * `EventLoops` runs, the platform responder and the service's own replay sources, and the replay topic of the outbox
 * holds the states and the ReplayCompleted marker. Only the event-log read is a list in memory.
 */
class ReplayResponderEndToEndTest {
    private val json = EnvelopeJson()
    private val replayJson = ReplayJson()

    private class Log : EventLogReader {
        private val records = mutableListOf<LogRecord>()

        fun add(envelopeJson: String) {
            records += LogRecord(OFFSETS.incrementAndGet(), envelopeJson)
        }

        fun lastOffset(): Long = records.maxOf { it.offset }

        override fun read(
            topic: String,
            afterOffset: Long,
            limit: Int,
        ): List<LogRecord> =
            if (topic == REQUEST_TOPIC) records.filter { it.offset > afterOffset }.take(limit) else emptyList()

        private companion object {
            // the offset row of tenancy-svc on the request topic is platform-scoped and the database is shared
            val OFFSETS = AtomicLong(System.currentTimeMillis() * 1000)
        }
    }

    private class Stored(
        val type: String,
        val tenant: String,
        val data: String,
    )

    private class Env(
        val context: AssertableWebApplicationContext,
        val log: Log,
    ) {
        val driver: InboxConsumer =
            context.getBeansOfType(InboxConsumer::class.java).values.single {
                it is ReplayResponderDriver
            }
        private val tenantsApi: TenantsController = context.getBean(TenantsController::class.java)
        private val entitiesApi: EntitiesController = context.getBean(EntitiesController::class.java)
        private val tenants: TenantContext = context.getBean(TenantContext::class.java)

        /** One tenant with 1 group and 2 live entities: 3 structure nodes. */
        fun newTenant(): TenantId {
            val contract = "C-${UUID.randomUUID()}"
            val ref = TenantRefs.refOfContract(contract)
            tenantsApi.createTenant(
                FIXED_FLOW_CONTEXT,
                UUID.randomUUID(),
                CreateTenantRequest(contract, "Tenant $contract", "PLAN-A", ref),
            )
            val tenant = TenantRefs.idOf(ref)
            tenants.bind(tenant, "user:test") {
                val group =
                    entitiesApi
                        .createGroup(
                            UUID.randomUUID(),
                            CreateGroupRequest(
                                tenant.value.toString(),
                                "Group ${UUID.randomUUID()}",
                                CreateGroupRequest.GroupKind.GST_GROUP,
                                "M2-GSTN",
                            ),
                        ).body!!
                repeat(2) { createEntity(tenant, group.groupRef) }
            }
            return tenant
        }

        private fun createEntity(
            tenant: TenantId,
            groupRef: String,
        ) {
            val entity =
                entitiesApi.createEntity(
                    UUID.randomUUID(),
                    CreateEntityRequest(
                        tenant.value.toString(),
                        groupRef,
                        "Entity ${UUID.randomUUID()}",
                        CreateEntityRequest.Constitution.PRIVATE_LIMITED,
                        null,
                        null,
                        "H" +
                            UUID
                                .randomUUID()
                                .toString()
                                .take(8)
                                .uppercase()
                                .replace("-", "A"),
                    ),
                )
            // a status entry is what makes an entity part of the EntityProfileChanged replay
            entitiesApi.setEntityStatus(
                entity.body!!.entityRef,
                UUID.randomUUID(),
                SetEntityStatusRequest(
                    SetEntityStatusRequest.StatusKind.PARTIAL_EXEMPTION,
                    java.time.LocalDate.of(2026, 7, 1),
                    partiallyExempt = true,
                    basis = "partial exemption review",
                ),
            )
        }
    }

    private fun run(
        container: BoundaryDatabase = ReplayResponderDatabase,
        block: (Env, BoundaryDatabase) -> Unit,
    ) {
        val log = Log()
        container
            .runner()
            .withBean(EventLogReader::class.java, { log })
            .run { context ->
                context.startupFailure?.let { throw it }
                block(Env(context, log), container)
            }
    }

    private fun request(
        replayId: UUID,
        producer: String,
    ): String =
        json.write(
            Envelope(
                id = EventId("req-$replayId"),
                source = "/gst/access-svc",
                type = "com.dynokore.gst.platform.ReplayRequested.v1",
                subject = "replay/$replayId",
                time = Instant.parse("2026-11-19T10:00:00Z"),
                tenant = TenantId.PLATFORM,
                entity = EntityRef.PLATFORM,
                docUuid = null,
                transactionDate = null,
                docSequence = null,
                instanceId = "access-svc-0",
                correlation = CorrelationId("corr-$replayId"),
                causation = CausationId("cause-$replayId"),
                dataJson = replayJson.writeRequest(ReplayRequest(replayId, producer, ReplayMode.FULL_SYNC, null)),
            ),
        )

    /** What the responder wrote to [topic] for [replayId], read as the database owner (RLS hides it otherwise). */
    private fun written(
        db: BoundaryDatabase,
        topic: String,
        replayId: UUID,
    ): List<Stored> =
        DriverManager.getConnection(db.container.jdbcUrl, db.container.username, db.container.password).use { c ->
            c
                .prepareStatement(
                    "select type, tenant_id::text, data::text from tenancy.outbox " +
                        "where topic = ? and correlation_id = ? order by seq",
                ).use { st ->
                    st.setString(1, topic)
                    st.setString(2, "replay:$replayId")
                    st.executeQuery().use { rs ->
                        generateSequence {
                            if (rs.next()) {
                                Stored(
                                    rs.getString(1),
                                    rs.getString(2),
                                    rs.getString(3),
                                )
                            } else {
                                null
                            }
                        }.toList()
                    }
                }
        }

    private fun count(
        db: BoundaryDatabase,
        sql: String,
        arg: String,
    ): Long =
        DriverManager.getConnection(db.container.jdbcUrl, db.container.username, db.container.password).use { c ->
            c.prepareStatement(sql).use { st ->
                st.setString(1, arg)
                st.executeQuery().use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        }

    private fun nextOffset(db: BoundaryDatabase): Long =
        DriverManager.getConnection(db.container.jdbcUrl, db.container.username, db.container.password).use { c ->
            c
                .prepareStatement(
                    "select next_offset from tenancy.consumer_offset where consumer = 'tenancy-svc' and topic = ?",
                ).use { st ->
                    st.setString(1, REQUEST_TOPIC)
                    st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else -1L }
                }
        }

    @Test
    fun `INV-AC-25010 a full sync request for tenancy-svc is answered with the states and then ReplayCompleted`() {
        run { env, db ->
            val tenant = env.newTenant()
            val replayId = UUID.randomUUID()
            env.log.add(request(replayId, "tenancy-svc"))

            assertThat(env.driver.pollOnce().applied).isEqualTo(1)

            val rows = written(db, REPLAY_TOPIC, replayId)
            val states = rows.dropLast(1)
            assertThat(rows.last().type).isEqualTo("com.dynokore.gst.platform.ReplayCompleted.v1")
            assertThat(rows.last().data).contains(replayId.toString())
            assertThat(rows.last().data.replace(" ", "")).contains("\"eventsReplayed\":${states.size}")
            val mine = states.filter { it.tenant == tenant.value.toString() }
            val nodes = mine.filter { it.type == STRUCTURE_TYPE }
            // the group and the 2 live entities of the task, plus the tenant's own node the source emits by design
            assertThat(nodes.filterNot { it.data.replace(" ", "").contains("\"kind\":\"TENANT\"") }).hasSize(3)
            assertThat(nodes).hasSize(4)
            assertThat(mine.filter { it.type == PROFILE_TYPE }).isNotEmpty()
            assertThat(states.map { it.type }).containsOnly(STRUCTURE_TYPE, PROFILE_TYPE)
        }
    }

    @Test
    fun `INV-AC-25010 the request's inbox row and the position past it are stored`() {
        run { env, db ->
            env.newTenant()
            val replayId = UUID.randomUUID()
            env.log.add(request(replayId, "tenancy-svc"))
            env.driver.pollOnce()

            assertThat(
                count(
                    db,
                    "select count(*) from tenancy.inbox where consumer = 'tenancy-svc' and event_id = ?",
                    "req-$replayId",
                ),
            ).isEqualTo(1)
            assertThat(nextOffset(db)).isGreaterThan(env.log.lastOffset())
        }
    }

    @Test
    fun `INV-AC-25010 a request for another producer writes nothing to any replay topic and is acknowledged`() {
        run { env, db ->
            val other = UUID.randomUUID()
            val prefixed = UUID.randomUUID()
            env.log.add(request(other, "masterdata-svc"))
            env.log.add(request(prefixed, "tenancy-svc-x"))

            assertThat(env.driver.pollOnce().applied).isZero()

            for (id in listOf(other, prefixed)) {
                assertThat(written(db, REPLAY_TOPIC, id)).isEmpty()
                assertThat(
                    count(db, "select count(*) from tenancy.outbox where correlation_id = ?", "replay:$id"),
                ).isZero()
                assertThat(
                    count(
                        db,
                        "select count(*) from tenancy.inbox where consumer = 'tenancy-svc' and event_id = ?",
                        "req-$id",
                    ),
                ).isEqualTo(1)
            }
            assertThat(nextOffset(db)).isGreaterThan(env.log.lastOffset())
        }
    }

    @Test
    fun `INV-AC-25010 a second poll answers nothing and the driver is found by the type EventLoops drives`() {
        run { env, db ->
            val replayId = UUID.randomUUID()
            env.log.add(request(replayId, "tenancy-svc"))
            assertThat(env.driver.pollOnce().applied).isEqualTo(1)
            val before = written(db, REPLAY_TOPIC, replayId).size

            assertThat(env.driver.pollOnce().applied).isZero()
            assertThat(written(db, REPLAY_TOPIC, replayId)).hasSize(before)
            assertThat(
                env.context.getBeansOfType(InboxConsumer::class.java).values,
            ).anyMatch { it is ReplayResponderDriver }
        }
    }

    @Test
    fun `INV-AC-25010 a failure of the responder run is rethrown by the driver`() {
        val failure = IllegalStateException("run failed")
        val thrown = runCatching { ReplayResponderDriver { throw failure }.pollOnce() }.exceptionOrNull()
        assertThat(thrown).isSameAs(failure)
    }

    private companion object {
        const val REQUEST_TOPIC = "gst.access.replay-requested.v1"
        const val REPLAY_TOPIC = "gst.tenancy.replay-access.v1"
        const val STRUCTURE_TYPE = "com.dynokore.gst.tenancy.OrganisationStructureChanged.v1"
        const val PROFILE_TYPE = "com.dynokore.gst.tenancy.EntityProfileChanged.v1"
    }
}

/**
 * The end-to-end test has its own PostgreSQL: the responder answers for every tenant of the database, and the shared
 * [BoundaryFixture] holds the entities other tests leave with an election and no status entry, which the profile
 * replay source cannot answer (reported as a gap in the MR of INV-T-25012).
 */
private object ReplayResponderDatabase : BoundaryDatabase(PgTestImages.PG17, "c-inv-t-25012")
