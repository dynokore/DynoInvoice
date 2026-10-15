package com.dynokore.platform.serviceruntime.pool

import com.dynokore.platform.serviceruntime.composition.ServiceDataSources
import com.dynokore.platform.serviceruntime.datasource.CredentialRotationIT.Companion.postgres
import com.dynokore.platform.serviceruntime.datasource.CredentialRotationIT.Companion.query
import com.dynokore.platform.serviceruntime.datasource.CredentialRotationIT.Companion.sql
import com.dynokore.platform.serviceruntime.datasource.tpl18Service
import com.dynokore.platform.serviceruntime.datasource.withMountedLogin
import com.dynokore.platform.serviceruntime.pool.LaneGateHolder
import com.dynokore.platform.tenancykernel.TenantAwareDataSource
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLTransientConnectionException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
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

    private val postgres: PostgreSQLContainer<*> =
        postgres(IMAGE).also {
            it.sql("create role $RUNTIME login password '$PASSWORD'")
        }

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
            val dataSource: DataSource = TenantAwareDataSource(pool, gate)
            gate.budgets shouldBe LaneBudgets(4, 2, 1)
            pool.maximumPoolSize shouldBe 7
            val background = Executors.newFixedThreadPool(3)
            try {
                // the background lane saturated (2 of 2) and the reprocessing lane (1 of 1), held on other threads
                val held =
                    (1..2).map { background.take(dataSource, PoolLane.BACKGROUND) } +
                        background.take(dataSource, PoolLane.REPROCESSING)
                val others = held.map { it.get(TIMEOUT_MS * 4, TimeUnit.MILLISECONDS) }
                // a further background connection waits for its own permits and times out
                shouldThrow<SQLTransientConnectionException> {
                    PoolLanes.inLane(
                        PoolLane.BACKGROUND,
                    ) { dataSource.connection.close() }
                }

                // every request permit is served at once, from the connections the other lanes are not owed
                val started = System.nanoTime()
                val requests = (1..4).map { dataSource.connection }
                (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < TIMEOUT_MS) shouldBe true
                // the request lane is bounded by its own budget too
                shouldThrow<SQLTransientConnectionException> { dataSource.connection.close() }

                requests.forEach { it.close() }
                others.forEach { it.close() }
                PoolLane.entries.forEach { gate.availablePermits(it) shouldBe gate.budgets.of(it) }
            } finally {
                background.shutdownNow()
            }
        }
    }

    @Test
    fun `a lane with a budget of 0 is refused, never run on a live lane`() {
        service("access-svc", "service-runtime.pool.profile=minimal").run { context ->
            context.startupFailure shouldBe null
            val pool = context.getBean(DataSource::class.java).shouldBeInstanceOf<HikariDataSource>()
            val gate = context.getBean(LaneGateHolder::class.java).permits()
            val dataSource: DataSource = TenantAwareDataSource(pool, gate)
            gate.budgets shouldBe LaneBudgets(4, 2, 0)
            val refused =
                shouldThrow<SQLTransientConnectionException> {
                    PoolLanes.inLane(PoolLane.REPROCESSING) { dataSource.connection.close() }
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
            val tenants = context.getBean(TenantContext::class.java)
            val tenant = TenantId(UUID.randomUUID())
            PoolLane.entries.forEach { lane ->
                // commit: the lane's name during the transaction, the pool's own name on the same connection after it
                val (pid, during) =
                    PoolLanes.inLane(lane) {
                        tenants.bind(tenant, "user-a") { tx.inTransaction { tx.require().backend() } }
                    }
                during shouldBe "submission-svc:${lane.name.lowercase()}"
                postgres.applicationNameOf(pid) shouldBe "submission-svc"
                // rollback: likewise
                var rolledBack = 0
                shouldThrow<IllegalStateException> {
                    PoolLanes.inLane(lane) {
                        tenants.bind(tenant, "user-a") {
                            tx.inTransaction {
                                val (p, name) = tx.require().backend()
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
        postgres(IMAGE).use { eventLog ->
            eventLog.sql("create role $RUNTIME login password '$PASSWORD'")
            service("submission-svc", "service-runtime.pool.profile=ha", "gst.db.event-log-url=${eventLog.jdbcUrl}")
                // the event-log pool copies the SecretStore credentials provider (INV-Q-4570)
                .run { context ->
                    context.startupFailure shouldBe null
                    context.getBean(LaneGateHolder::class.java).permits().budgets shouldBe
                        LaneBudgets(7, 3, 0)
                    val pool = context.getBean(ServiceDataSources.EVENT_LOG, HikariDataSource::class.java)
                    pool.maximumPoolSize shouldBe LaneBudgets.EVENT_LOG.maximum
                    pool.connection.use { it.backend().second shouldBe "submission-svc" }
                }
        }
    }

    /** Takes a connection in [lane] on this executor and keeps it open until the test closes it. */
    private fun java.util.concurrent.ExecutorService.take(
        dataSource: DataSource,
        lane: PoolLane,
    ): CompletableFuture<Connection> =
        CompletableFuture.supplyAsync({
            PoolLanes.inLane(lane) { dataSource.connection }
        }, this)

    /** This backend's pid and its application_name as pg_stat_activity shows it. */
    private fun Connection.backend(): Pair<Int, String> =
        createStatement().use { statement ->
            statement
                .executeQuery(
                    "select pid, application_name from pg_stat_activity where pid = pg_backend_pid()",
                ).use {
                    it.next()
                    it.getInt(1) to it.getString(2)
                }
        }

    /** [pid]'s application_name, seen from another session. */
    private fun PostgreSQLContainer<*>.applicationNameOf(pid: Int): String =
        query("select application_name from pg_stat_activity where pid = $pid")

    private companion object {
        const val IMAGE = "postgres:17"
        const val RUNTIME = "submission_runtime"
        const val PASSWORD = "runtime-pw"
        const val TIMEOUT_MS = 1000L
    }
}
