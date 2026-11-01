package com.dynokore.gst.transport.adapters

import com.dynokore.gst.transport.adapters.persistence.JooqAccessPointConnectionStore
import com.dynokore.gst.transport.adapters.persistence.JooqApConnections
import com.dynokore.gst.transport.adapters.persistence.JooqIssuanceReplica
import com.dynokore.gst.transport.adapters.persistence.jooq.tables.ApConnection.Companion.AP_CONNECTION
import com.dynokore.gst.transport.adapters.persistence.jooq.tables.IssuanceModeReplica.Companion.ISSUANCE_MODE_REPLICA
import com.dynokore.gst.transport.application.ElectAcknowledgementRoute
import com.dynokore.gst.transport.application.RouteChangePublisher
import com.dynokore.gst.transport.application.RouteChangeRecorder
import com.dynokore.gst.transport.application.RouteUnitOfWork
import com.dynokore.gst.transport.domain.AcknowledgementRoute
import com.dynokore.gst.transport.domain.AdapterId
import com.dynokore.gst.transport.domain.ConnectionRouteState
import com.dynokore.gst.transport.domain.EntityRef
import com.dynokore.gst.transport.domain.IssuanceModeEntry
import com.dynokore.gst.transport.domain.IssuanceReplicaRow
import com.dynokore.gst.transport.domain.TenantRef
import com.dynokore.gst.transport.domain.ThroughputCeiling
import com.dynokore.platform.tenancyjooq.TableClass
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancyjooq.TenancyTables
import com.dynokore.platform.tenancykernel.BindingKeySource
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.impl.DefaultConfiguration
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import java.sql.SQLException
import java.time.LocalDate
import java.util.UUID

/**
 * The jOOQ connection store and issuanceMode replica against the real database, through the runtime login with a
 * signed binding (CS-19; INV-T-12485). The runner binds the tenant of [Session] as the first statement of every
 * outermost transaction, as the platform runner does; a nested call joins the open transaction.
 */
class RouteAndReplicaStoresTest {
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
                            DSL.name("transport", "issuance_mode_replica") to TableClass.TENANT,
                        ),
                    ),
                    tenants,
                    runnerImpl,
                ).dsl()

        /** Every transaction of this session is bound to its tenant, as the platform runner binds it. */
        val runner =
            object : TransactionRunner {
                override fun <T> inTransaction(block: () -> T): T =
                    tenants.bind(TenantId(tenant), SignedBindings.ACTOR) { runnerImpl.inTransaction(block) }
            }

        val tenantRef = TenantRef(tenant.toString())
        val connections = JooqAccessPointConnectionStore(dsl, runner)
        val replica = JooqIssuanceReplica(dsl, runner, tenants)

        fun insertConnection(adapter: String) =
            runner.inTransaction {
                dsl
                    .insertInto(AP_CONNECTION)
                    .set(AP_CONNECTION.TENANT_ID, tenant)
                    .set(AP_CONNECTION.ADAPTER_ID, adapter)
                    .set(AP_CONNECTION.AGREED_RATE, 100.toBigDecimal())
                    .execute()
            }

        fun setRoute(value: String) =
            runner.inTransaction {
                dsl.update(AP_CONNECTION).set(AP_CONNECTION.ACKNOWLEDGEMENT_ROUTE, value).execute()
            }

        fun versions(): Pair<Long, Long> =
            runner.inTransaction {
                dsl
                    .select(AP_CONNECTION.VERSION, AP_CONNECTION.ROUTE_VERSION)
                    .from(AP_CONNECTION)
                    .fetchOne()!!
                    .let { checkNotNull(it.value1()) to checkNotNull(it.value2()) }
            }

        fun replicaRows(): Int = runner.inTransaction { dsl.fetchCount(ISSUANCE_MODE_REPLICA) }
    }

    private fun ready(adapter: String = "primary") = Session(UUID.randomUUID()).also { it.insertConnection(adapter) }

    private class Counting : RouteChangePublisher {
        val versions = mutableListOf<Long>()

        override fun publish(
            tenant: TenantRef,
            connectionRef: String,
            route: AcknowledgementRoute?,
            entityRefs: List<EntityRef>,
            version: Long,
        ) {
            versions += version
        }
    }

    @Test
    fun `a new connection has no route, no entity refs and route version 0`() {
        val s = ready()
        s.connections.load(s.tenantRef, "primary") shouldBe ConnectionRouteState(null, emptyList(), 0)
    }

    @Test
    fun `the state saved is the state loaded, route, entity refs in order and version`() {
        val s = ready()
        val state = ConnectionRouteState(AcknowledgementRoute.POLLING, listOf(EntityRef("E-2"), EntityRef("E-1")), 3)
        s.connections.save(s.tenantRef, "primary", state)
        s.connections.load(s.tenantRef, "primary") shouldBe state
    }

    @Test
    fun `a ceiling save between two elections does not skip a version`() {
        val s = ready()
        val publisher = Counting()
        val unit =
            object : RouteUnitOfWork {
                override fun <T> run(block: () -> T): T = s.runner.inTransaction(block)
            }
        val elect = ElectAcknowledgementRoute(RouteChangeRecorder(s.connections, publisher, unit))
        elect.elect(s.tenantRef, "primary", "POLLING")
        JooqApConnections(
            s.dsl,
            s.runner,
        ).saveCeiling(AdapterId("primary"), ThroughputCeiling.restore(100.toBigDecimal(), 50.toBigDecimal()))
        elect.elect(s.tenantRef, "primary", "AP_CALLBACK")
        publisher.versions shouldContainExactly listOf(1L, 2L)
        s.versions() shouldBe (1L to 2L)
    }

    @Test
    fun `the store and the event roll back together when the publisher fails after the route write`() {
        val s = ready()
        val failing =
            object : RouteChangePublisher {
                override fun publish(
                    tenant: TenantRef,
                    connectionRef: String,
                    route: AcknowledgementRoute?,
                    entityRefs: List<EntityRef>,
                    version: Long,
                ): Unit = error("forced failure after the route write")
            }
        val unit =
            object : RouteUnitOfWork {
                override fun <T> run(block: () -> T): T = s.runner.inTransaction(block)
            }
        shouldThrow<IllegalStateException> {
            ElectAcknowledgementRoute(
                RouteChangeRecorder(s.connections, failing, unit),
            ).elect(s.tenantRef, "primary", "POLLING")
        }
        s.connections.load(s.tenantRef, "primary") shouldBe ConnectionRouteState(null, emptyList(), 0)
    }

    @Test
    fun `a route value outside the three is refused by the database check`() {
        val s = ready()
        listOf("FOURTH_VALUE", "polling", "").forEach { value ->
            val failure = shouldThrow<Exception> { s.setRoute(value) }
            generateSequence<Throwable>(failure) { t -> t.cause }.any { t -> t is SQLException } shouldBe true
        }
        s.connections
            .load(s.tenantRef, "primary")
            ?.route
            .shouldBeNull()
    }

    @Test
    fun `another tenant's connection is not found by its connectionRef`() {
        val owner = ready()
        val other = Session(UUID.randomUUID())
        other.connections.load(other.tenantRef, "primary").shouldBeNull()
        other.connections.load(owner.tenantRef, "primary").shouldBeNull()
    }

    @Test
    fun `a connectionRef differing in case does not find the record`() {
        val s = ready("primary")
        s.connections.load(s.tenantRef, "PRIMARY").shouldBeNull()
        s.connections.load(s.tenantRef, "primary") shouldBe ConnectionRouteState(null, emptyList(), 0)
    }

    private fun row(
        s: Session,
        version: Long,
        vararg entries: Pair<String, String>,
    ) = IssuanceReplicaRow(
        s.tenantRef,
        entries.map { IssuanceModeEntry(it.first, LocalDate.parse(it.second)) },
        version,
    )

    @Test
    fun `a replica row of another tenant's entity with the same entity ref is not read`() {
        val a = Session(UUID.randomUUID())
        val b = Session(UUID.randomUUID())
        a.replica.replace(EntityRef("E-001"), row(a, 4, "OBSERVE" to "2026-01-01"))
        b.replica.find(EntityRef("E-001")).shouldBeNull()
        a.replica.find(EntityRef("E-001")) shouldBe row(a, 4, "OBSERVE" to "2026-01-01")
    }

    @Test
    fun `the replica row is replaced whole`() {
        val s = Session(UUID.randomUUID())
        s.replica.replace(EntityRef("E-001"), row(s, 1, "RELAY" to "2026-01-01", "OBSERVE" to "2026-06-01"))
        s.replica.replace(EntityRef("E-001"), row(s, 2, "NONE" to "2026-09-01"))
        s.replica.find(EntityRef("E-001")) shouldBe row(s, 2, "NONE" to "2026-09-01")
        s.replicaRows() shouldBe 1
    }

    @Test
    fun `a replica row written for a tenant other than the bound one is refused`() {
        val s = Session(UUID.randomUUID())
        val foreign = IssuanceReplicaRow(TenantRef(UUID.randomUUID().toString()), emptyList(), 1)
        shouldThrow<IllegalArgumentException> { s.replica.replace(EntityRef("E-001"), foreign) }
    }

    @Test
    fun `the replica table holds the entries and the structure version and no other attribute`() {
        TransportDb.superuser().use { c ->
            c
                .prepareStatement(
                    "select string_agg(column_name, ',' order by column_name) from information_schema.columns " +
                        "where table_schema = 'transport' and table_name = 'issuance_mode_replica'",
                ).use { st ->
                    st.executeQuery().use { rs ->
                        rs.next()
                        rs.getString(1) shouldBe "entity_ref,entries,structure_version,tenant_id"
                    }
                }
        }
    }
}
