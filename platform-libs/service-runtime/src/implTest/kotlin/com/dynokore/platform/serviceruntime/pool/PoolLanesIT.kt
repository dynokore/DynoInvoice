package com.dynokore.platform.serviceruntime.pool

import com.dynokore.platform.serviceruntime.composition.ServiceDataSources
import com.dynokore.platform.serviceruntime.datasource.tpl18Service
import com.dynokore.platform.serviceruntime.datasource.withMountedLogin
import com.dynokore.platform.serviceruntime.pool.LaneGateHolder
import com.dynokore.platform.serviceruntime.testing.BACKEND_PID
import com.dynokore.platform.serviceruntime.testing.CATALOG_TABLES
import com.dynokore.platform.serviceruntime.testing.HeldTransaction
import com.dynokore.platform.serviceruntime.testing.PG_STAT_ACTIVITY
import com.dynokore.platform.serviceruntime.testing.PG_STAT_ACTIVITY_NAME
import com.dynokore.platform.serviceruntime.testing.PG_STAT_ACTIVITY_PID
import com.dynokore.platform.serviceruntime.testing.TestDatabase
import com.dynokore.platform.serviceruntime.testing.createLoginSql
import com.dynokore.platform.serviceruntime.testing.setting
import com.dynokore.platform.serviceruntime.testing.startPostgres
import com.dynokore.platform.tenancykernel.TenantAwareDataSource
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Path
import java.sql.SQLTransientConnectionException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * The application pool's lanes against PostgreSQL (INV-REQ-1230 b1, INV-REQ-1072 b5, INV-ADR-3968 item 1; owner
 * ruling INV-Q-4023), composed as TPL-18 writes a service (P109): with the background and reprocessing lanes
 * saturated, every request permit is still served at once; a lane's transaction shows `<gst.service>:<lane>` in
 * pg_stat_activity and the connection shows `<gst.service>` again after commit and after rollback (coordinator ruling
 * INV-Q-4487); a lane with a budget of 0 is refused, never served from another lane.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PoolLanesIT {
    // the test login is mounted in SecretStore (INV-Q-4570, INV-Q-4603)
    @TempDir
    lateinit var mountRoot: Path

    private val postgres: PostgreSQLContainer<*> = startPostgres(IMAGE, createLoginSql(RUNTIME, PASSWORD))

    @AfterAll
    fun stop() = postgres.stop()

    private fun service(
        service: String,
        vararg properties: String,
    ) = tpl18Service()
        .withPropertyValues(
            "spring.datasource.url=${postgres.jdbcUrl}",
            "spring.datasource.username=$RUNTIME",
            "spring.datasource.hikari.connection-timeout=$TIMEOUT_MS",
            "gst.service=$service",
            *properties,
        ).withMountedLogin(mountRoot, PASSWORD)

    @Test
    fun `a saturated background and reprocessing lane never blocks a request`() {
        service(
            "submission-svc",
            "service-runtime.pool.profile=minimal",
            "service-runtime.pool.runs-reprocessing=true",
        ).run { context ->
            context.startupFailure shouldBe null
            val pool = context.getBean(DataSource::class.java).shouldBeInstanceOf<HikariDataSource>()
            val gate = context.getBean(LaneGateHolder::class.java).permits()
            val access =
                TestDatabase(postgres.jdbcUrl, emptyMap()).use { db ->
                    db.over(TenantAwareDataSource(pool, gate))
                }
            gate.budgets shouldBe LaneBudgets(4, 2, 1)
            pool.maximumPoolSize shouldBe 7
            // the background lane saturated (2 of 2) and the reprocessing lane (1 of 1), held on other threads
            val held =
                (1..2).map { take(access, PoolLane.BACKGROUND) } + take(access, PoolLane.REPROCESSING)
            try {
                // a further background connection waits for its own permits and times out
                shouldThrow<SQLTransientConnectionException> {
                    PoolLanes.inLane(PoolLane.BACKGROUND) { access.asPlatform { } }
                }

                // every request permit is served at once, from the connections the other lanes are not owed
                val started = System.nanoTime()
                val requests = (1..4).map { HeldTransaction(access) }
                (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < TIMEOUT_MS) shouldBe true
                // the request lane is bounded by its own budget too
                shouldThrow<SQLTransientConnectionException> { access.asPlatform { } }

                requests.forEach { it.close() }
                held.forEach { it.close() }
                PoolLane.entries.forEach { gate.availablePermits(it) shouldBe gate.budgets.of(it) }
            } finally {
                held.forEach { it.close() }
            }
        }
    }

    @Test
    fun `a lane with a budget of 0 is refused, never run on a live lane`() {
        service("access-svc", "service-runtime.pool.profile=minimal").run { context ->
            context.startupFailure shouldBe null
            val pool = context.getBean(DataSource::class.java).shouldBeInstanceOf<HikariDataSource>()
            val gate = context.getBean(LaneGateHolder::class.java).permits()
            val access =
                TestDatabase(postgres.jdbcUrl, emptyMap()).use { db ->
                    db.over(TenantAwareDataSource(pool, gate))
                }
            gate.budgets shouldBe LaneBudgets(4, 2, 0)
            val refused =
                shouldThrow<SQLTransientConnectionException> {
                    PoolLanes.inLane(PoolLane.REPROCESSING) { access.asPlatform { } }
                }
            refused.message shouldBe
                "the REPROCESSING lane has no permits (budget 0) and never runs on another lane (INV-REQ-1072 b5)"
            PoolLane.entries.forEach { gate.availablePermits(it) shouldBe gate.budgets.of(it) }
        }
    }

    @Test
    fun `a lane's transaction shows its application_name, which ends at commit and at rollback`() {
        service(
            "submission-svc",
            "service-runtime.pool.runs-reprocessing=true",
            "service-runtime.pool.lanes.request=1",
            "service-runtime.pool.lanes.background=1",
            "service-runtime.pool.lanes.reprocessing=1",
        ).run { context ->
            context.startupFailure shouldBe null
            val tx = context.getBean(TransactionRunnerImpl::class.java)
            val dsl = context.getBean(DSLContext::class.java)
            val tenants = context.getBean(TenantContext::class.java)
            val tenant = TenantId(UUID.randomUUID())
            PoolLane.entries.forEach { lane ->
                // commit: the lane's name during the transaction, the pool's own name on the same connection after it
                val (pid, during) =
                    PoolLanes.inLane(lane) {
                        tenants.bind(tenant, "user-a") { tx.inTransaction { dsl.backend() } }
                    }
                during shouldBe "submission-svc:${lane.name.lowercase()}"
                postgres.applicationNameOf(pid) shouldBe "submission-svc"
                // rollback: likewise
                var rolledBack = 0
                shouldThrow<IllegalStateException> {
                    PoolLanes.inLane(lane) {
                        tenants.bind(tenant, "user-a") {
                            tx.inTransaction {
                                val (p, name) = dsl.backend()
                                name shouldBe "submission-svc:${lane.name.lowercase()}"
                                rolledBack = p
                                error("rolled back")
                            }
                        }
                    }
                }
                postgres.applicationNameOf(rolledBack) shouldBe "submission-svc"
            }
        }
    }

    @Test
    fun `the HA event-log pool's connections carry the pool's own name`() {
        startPostgres(IMAGE, createLoginSql(RUNTIME, PASSWORD)).use { eventLog ->
            service("submission-svc", "service-runtime.pool.profile=ha", "gst.db.event-log-url=${eventLog.jdbcUrl}")
                // the event-log pool copies the SecretStore credentials provider (INV-Q-4570)
                .run { context ->
                    context.startupFailure shouldBe null
                    context.getBean(LaneGateHolder::class.java).permits().budgets shouldBe
                        LaneBudgets(7, 3, 0)
                    val pool = context.getBean(ServiceDataSources.EVENT_LOG, HikariDataSource::class.java)
                    pool.maximumPoolSize shouldBe LaneBudgets.EVENT_LOG.maximum
                    TestDatabase(eventLog.jdbcUrl, emptyMap()).use { db ->
                        db.over(pool).asPlatform { backend().second } shouldBe "submission-svc"
                    }
                }
        }
    }

    /** Takes a connection in [lane] on a helper thread and keeps its transaction open until the test closes it. */
    private fun take(
        access: TestDatabase.Access,
        lane: PoolLane,
    ) = HeldTransaction(access) { body -> PoolLanes.inLane(lane) { body() } }

    /** This backend's pid and its application_name, as the session itself reports them. */
    private fun DSLContext.backend(): Pair<Int, String> =
        select(BACKEND_PID, setting("application_name"))
            .fetchSingle()
            .let { it.value1() to it.value2() }

    /** [pid]'s application_name, seen from another session of the same login. */
    private fun PostgreSQLContainer<*>.applicationNameOf(pid: Int): String =
        TestDatabase(jdbcUrl, CATALOG_TABLES).use { db ->
            db.login(RUNTIME, PASSWORD).asPlatform {
                select(
                    PG_STAT_ACTIVITY_NAME,
                ).from(PG_STAT_ACTIVITY).where(PG_STAT_ACTIVITY_PID.eq(pid)).fetchSingle().value1()
            }
        }

    private companion object {
        const val IMAGE = "postgres:17"
        const val RUNTIME = "submission_runtime"
        const val PASSWORD = "runtime-pw"
        const val TIMEOUT_MS = 1000L
    }
}
