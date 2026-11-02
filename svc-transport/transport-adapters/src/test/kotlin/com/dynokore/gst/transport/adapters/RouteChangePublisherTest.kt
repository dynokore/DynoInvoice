package com.dynokore.gst.transport.adapters

import com.dynokore.gst.transport.adapters.persistence.JooqAccessPointConnectionStore
import com.dynokore.gst.transport.adapters.persistence.JooqApConnections
import com.dynokore.gst.transport.adapters.persistence.JooqRouteChangePublisher
import com.dynokore.gst.transport.adapters.persistence.jooq.tables.ApConnection.Companion.AP_CONNECTION
import com.dynokore.gst.transport.application.ElectAcknowledgementRoute
import com.dynokore.gst.transport.application.RouteChangePublisher
import com.dynokore.gst.transport.application.RouteChangeRecorder
import com.dynokore.gst.transport.application.RouteUnitOfWork
import com.dynokore.gst.transport.domain.AcknowledgementRoute
import com.dynokore.gst.transport.domain.AdapterId
import com.dynokore.gst.transport.domain.ConnectionRouteState
import com.dynokore.gst.transport.domain.EntityRef
import com.dynokore.gst.transport.domain.TenantRef
import com.dynokore.gst.transport.domain.ThroughputCeiling
import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.JdbcOutboxWriter
import com.dynokore.platform.tenancyjooq.TableClass
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancyjooq.TenancyTables
import com.dynokore.platform.tenancykernel.BindingKeySource
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.impl.DefaultConfiguration
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.io.File
import java.time.Clock
import java.util.UUID

/**
 * The jOOQ publisher of `AccessPointRouteChanged` against the real database (INV-T-15476): the outbox row is written
 * in the open transaction of the route write, with the tenant-wide entity (ruling INV-Q-15474 A), and its payload
 * conforms to the schema of the pinned platform-contracts bundle.
 */
class RouteChangePublisherTest {
    private companion object {
        const val TENANT_WIDE = "00000000-0000-0000-0000-000000000000"
        const val TYPE = "com.dynokore.gst.transport.AccessPointRouteChanged.v1"
        const val TOPIC = "gst.transport.access-point-route-changed.v1"
        val mapper = ObjectMapper()
    }

    private class OutboxRow(
        val tenant: UUID,
        val entityRef: String,
        val topic: String,
        val type: String,
        val data: JsonNode,
    )

    private class Session(
        val tenant: UUID,
    ) {
        private val tenants = TenantContextImpl()
        private val source =
            PGSimpleDataSource().apply {
                setUrl(TransportDb.jdbcUrl)
                user = TransportDb.RUNTIME
                password = TransportDb.RUNTIME_PASSWORD
            }
        private val runnerImpl =
            TransactionRunnerImpl(
                source,
                tenants,
                null,
                object : BindingKeySource {
                    override fun currentKey(): ByteArray = TransportDb.bindingKey.copyOf()

                    override fun previousKey(): ByteArray? = null
                },
            )
        val dsl: DSLContext =
            TenancyJooq
                .configure(
                    DefaultConfiguration().set(SQLDialect.POSTGRES),
                    TenancyTables.declared(
                        mapOf(
                            DSL.name("transport", "ap_connection") to TableClass.TENANT,
                            DSL.name("transport", "outbox") to TableClass.TENANT,
                            DSL.name("transport", "outbox_sequence") to TableClass.TENANT,
                        ),
                    ),
                    tenants,
                    runnerImpl,
                ).dsl()
        val runner =
            object : TransactionRunner {
                override fun <T> inTransaction(block: () -> T): T =
                    tenants.bind(TenantId(tenant), SignedBindings.ACTOR) { runnerImpl.inTransaction(block) }
            }
        val tenantRef = TenantRef(tenant.toString())
        val connections = JooqAccessPointConnectionStore(dsl, runner)
        val unit =
            object : RouteUnitOfWork {
                override fun <T> run(block: () -> T): T = runner.inTransaction(block)
            }
        val publisher =
            JooqRouteChangePublisher(
                JdbcOutboxWriter(
                    dsl,
                    "transport",
                    "transport-svc",
                    "test-instance",
                    EventCatalogue(
                        listOf(CatalogueEntry(TYPE, TOPIC, setOf("transport-svc"), contentTopic = false)),
                    ),
                    Clock.systemUTC(),
                ),
            )
        val recorder = RouteChangeRecorder(connections, publisher, unit)

        fun insertConnection(adapter: String) =
            runner.inTransaction {
                dsl
                    .insertInto(AP_CONNECTION)
                    .set(AP_CONNECTION.TENANT_ID, tenant)
                    .set(AP_CONNECTION.ADAPTER_ID, adapter)
                    .set(AP_CONNECTION.AGREED_RATE, 100.toBigDecimal())
                    .execute()
            }

        fun routeVersion(): Long =
            runner.inTransaction {
                checkNotNull(
                    dsl
                        .select(AP_CONNECTION.ROUTE_VERSION)
                        .from(AP_CONNECTION)
                        .fetchOne()
                        ?.value1(),
                )
            }

        /** Rows of the outbox for the given tenant, read as the database owner, in write order. */
        fun rows(of: UUID = tenant): List<OutboxRow> =
            TransportDb.superuser().use { c ->
                c
                    .prepareStatement(
                        "select tenant_id, entity_ref, topic, type, data::text from transport.outbox " +
                            "where tenant_id = ? order by seq",
                    ).use { st ->
                        st.setObject(1, of)
                        st.executeQuery().use { rs ->
                            buildList {
                                while (rs.next()) {
                                    add(
                                        OutboxRow(
                                            rs.getObject(1, UUID::class.java),
                                            rs.getString(2),
                                            rs.getString(3),
                                            rs.getString(4),
                                            mapper.readTree(rs.getString(5)),
                                        ),
                                    )
                                }
                            }
                        }
                    }
            }
    }

    private fun ready(adapter: String = "primary") = Session(UUID.randomUUID()).also { it.insertConnection(adapter) }

    private fun Session.change(refs: List<String>) {
        val state = connections.load(tenantRef, "primary")!!
        recorder.record(tenantRef, "primary", state.withEntityRefs(refs.map(::EntityRef)))
    }

    private val schema by lazy {
        val dir = File(System.getProperty("gst.contract.dir"), "schemas/access-point-route-changed-payload.schema.json")
        SchemaRegistry
            .withDefaultDialect(SpecificationVersion.DRAFT_2020_12) {}
            .getSchema(dir.readText())
    }

    private fun errors(payload: JsonNode) = schema.validate(payload).map { it.toString() }

    @Test
    fun `the row is on the channel, tenant-wide, with the bound tenant, also for a first election`() {
        val s = ready()
        ElectAcknowledgementRoute(s.recorder).elect(s.tenantRef, "primary", "POLLING")
        val row = s.rows().single()
        row.type shouldBe TYPE
        row.topic shouldBe TOPIC
        row.entityRef shouldBe TENANT_WIDE
        row.tenant shouldBe s.tenant
        row.data["connectionRef"].asString() shouldBe "primary"
        row.data["acknowledgementRoute"].asString() shouldBe "POLLING"
    }

    @Test
    fun `a ceiling save between two elections does not skip a version`() {
        val s = ready()
        val elect = ElectAcknowledgementRoute(s.recorder)
        elect.elect(s.tenantRef, "primary", "POLLING")
        JooqApConnections(
            s.dsl,
            s.runner,
        ).saveCeiling(AdapterId("primary"), ThroughputCeiling.restore(100.toBigDecimal(), 50.toBigDecimal()))
        elect.elect(s.tenantRef, "primary", "AP_CALLBACK")
        s.rows().map { it.data["version"].asLong() } shouldContainExactly listOf(1L, 2L)
        s.routeVersion() shouldBe 2L
    }

    @Test
    fun `the route write and the outbox row roll back together after a forced failure`() {
        val s = ready()
        val failing =
            object : RouteChangePublisher {
                override fun publish(
                    tenant: TenantRef,
                    connectionRef: String,
                    route: AcknowledgementRoute?,
                    entityRefs: List<EntityRef>,
                    version: Long,
                ) {
                    s.publisher.publish(tenant, connectionRef, route, entityRefs, version)
                    error("forced failure after the route write and the outbox row")
                }
            }
        shouldThrow<IllegalStateException> {
            ElectAcknowledgementRoute(RouteChangeRecorder(s.connections, failing, s.unit))
                .elect(s.tenantRef, "primary", "POLLING")
        }
        s.connections.load(s.tenantRef, "primary") shouldBe ConnectionRouteState(null, emptyList(), 0)
        s.rows().shouldBeEmpty()
    }

    @Test
    fun `the route write and the outbox row commit together`() {
        val s = ready()
        ElectAcknowledgementRoute(s.recorder).elect(s.tenantRef, "primary", "REPORT_IMPORT")
        s.connections.load(s.tenantRef, "primary")?.route shouldBe AcknowledgementRoute.REPORT_IMPORT
        s.rows().size shouldBe 1
    }

    @Test
    fun `the data validates against the pinned schema and fails without version or without entityRefs`() {
        val s = ready()
        s.change(listOf("E-1", "E-2"))
        val data = s.rows().single().data
        errors(data).shouldBeEmpty()
        errors((data.deepCopy() as ObjectNode).also { it.remove("version") }) shouldNotBe emptyList<String>()
        errors((data.deepCopy() as ObjectNode).also { it.remove("entityRefs") }) shouldNotBe emptyList<String>()
    }

    @Test
    fun `a cleared route publishes a payload without acknowledgementRoute`() {
        val s = ready()
        ElectAcknowledgementRoute(s.recorder).elect(s.tenantRef, "primary", "POLLING")
        val state = s.connections.load(s.tenantRef, "primary")!!
        s.recorder.record(s.tenantRef, "primary", state.clear())
        val data = s.rows().last().data
        data.has("acknowledgementRoute") shouldBe false
        data["version"].asLong() shouldBe 2L
        errors(data).shouldBeEmpty()
    }

    @Test
    fun `the row carries the bound tenant's id and no other tenant's`() {
        val a = ready()
        val b = ready()
        ElectAcknowledgementRoute(a.recorder).elect(a.tenantRef, "primary", "POLLING")
        a.rows().map { it.tenant }.distinct() shouldContainExactly listOf(a.tenant)
        b.rows().shouldBeEmpty()
        ElectAcknowledgementRoute(b.recorder).elect(b.tenantRef, "primary", "POLLING")
        b.rows().map { it.tenant }.distinct() shouldContainExactly listOf(b.tenant)
        a.rows().size shouldBe 1
    }

    @Test
    fun `the last entity removed writes one tenant-wide row with an empty entityRefs`() {
        val s = ready()
        s.change(listOf("E-1"))
        s.change(emptyList())
        val rows = s.rows()
        rows.size shouldBe 2
        val last = rows.last()
        last.entityRef shouldBe TENANT_WIDE
        last.tenant shouldBe s.tenant
        last.data["entityRefs"].size() shouldBe 0
        last.data["entityRefs"].isArray shouldBe true
    }

    @Test
    fun `a connection ref or an entity ref is never the entity_ref of the row`() {
        val s = ready()
        s.change(listOf("E-1", "E-2"))
        s.change(listOf("E-2"))
        s.change(emptyList())
        val refs = setOf("primary", "E-1", "E-2", s.tenant.toString())
        s.rows().forEach { (it.entityRef in refs) shouldBe false }
        s.rows().forEach { it.entityRef shouldBe TENANT_WIDE }
    }
}
