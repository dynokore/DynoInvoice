package com.dynokore.platform.serviceruntime.pool

import com.dynokore.platform.serviceruntime.composition.ServiceDataSources
import com.dynokore.platform.serviceruntime.datasource.CredentialRotationIT.Companion.postgres
import com.dynokore.platform.serviceruntime.datasource.CredentialRotationIT.Companion.sql
import com.dynokore.platform.serviceruntime.datasource.tpl18Service
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import java.sql.SQLTransientConnectionException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * The application pool's lanes against PostgreSQL (INV-REQ-1230 b1, INV-REQ-1072 b5, INV-ADR-3968 item 1; owner
 * ruling INV-Q-4023), composed as TPL-18 writes a service (P109): with the background and reprocessing lanes
 * saturated, every request permit is still served at once; each lane's connections carry its own `application_name`
 * in pg_stat_activity; a lane with a budget of 0 is refused, never served from another lane.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PoolLanesIT {
    private val postgres: PostgreSQLContainer<*> = postgres(IMAGE).also { it.sql("create role $RUNTIME login password '$PASSWORD'") }

    @AfterAll
    fun stop() = postgres.stop()

    private fun service(
        service: String,
        vararg properties: String,
    ) = tpl18Service().withPropertyValues(
        "spring.datasource.url=${postgres.jdbcUrl}",
        "spring.datasource.username=$RUNTIME",
        "spring.datasource.password=$PASSWORD",
        "spring.datasource.hikari.connection-timeout=$TIMEOUT_MS",
        "gst.service=$service",
        *properties,
    )

    @Test
    fun `a saturated background and reprocessing lane never blocks a request, and each lane has its application_name`() {
        service("submission-svc", "service-runtime.pool.profile=minimal").run { context ->
            context.startupFailure shouldBe null
            val dataSource = context.getBean(DataSource::class.java).shouldBeInstanceOf<LanedDataSource>()
            dataSource.budgets shouldBe LaneBudgets(4, 2, 1)
            dataSource.unwrap(HikariDataSource::class.java).maximumPoolSize shouldBe 7
            val background = Executors.newFixedThreadPool(3)
            try {
                // the background lane saturated (2 of 2) and the reprocessing lane (1 of 1), held on other threads
                val held = (1..2).map { background.take(dataSource, PoolLane.BACKGROUND) } + background.take(dataSource, PoolLane.REPROCESSING)
                val others = held.map { it.get(TIMEOUT_MS * 4, TimeUnit.MILLISECONDS) }
                // a further background connection waits for its own permits and times out
                shouldThrow<SQLTransientConnectionException> { PoolLanes.inLane(PoolLane.BACKGROUND) { dataSource.connection.close() } }

                // every request permit is served at once, from the connections the other lanes are not owed
                val started = System.nanoTime()
                val requests = (1..4).map { dataSource.connection }
                (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < TIMEOUT_MS) shouldBe true
                requests.first().lanes() shouldBe
                    mapOf("submission-svc:request" to 4, "submission-svc:background" to 2, "submission-svc:reprocessing" to 1)
                // the request lane is bounded by its own budget too
                shouldThrow<SQLTransientConnectionException> { dataSource.connection.close() }

                requests.forEach { it.close() }
                others.forEach { it.close() }
                PoolLane.entries.forEach { dataSource.availablePermits(it) shouldBe dataSource.budgets.of(it) }
            } finally {
                background.shutdownNow()
            }
        }
    }

    @Test
    fun `a lane with a budget of 0 is refused, never run on a live lane`() {
        service("access-svc", "service-runtime.pool.profile=minimal").run { context ->
            context.startupFailure shouldBe null
            val dataSource = context.getBean(DataSource::class.java).shouldBeInstanceOf<LanedDataSource>()
            dataSource.budgets shouldBe LaneBudgets(4, 2, 0)
            val refused = shouldThrow<SQLTransientConnectionException> { PoolLanes.inLane(PoolLane.REPROCESSING) { dataSource.connection.close() } }
            refused.message shouldBe "the REPROCESSING lane has no permits (budget 0) and never runs on another lane (INV-REQ-1072 b5)"
            PoolLane.entries.forEach { dataSource.availablePermits(it) shouldBe dataSource.budgets.of(it) }
        }
    }

    @Test
    fun `the HA event-log pool's connections carry the background lane's application_name`() {
        postgres(IMAGE).use { eventLog ->
            eventLog.sql("create role $RUNTIME login password '$PASSWORD'")
            service("submission-svc", "service-runtime.pool.profile=ha", "gst.db.event-log-url=${eventLog.jdbcUrl}").run { context ->
                context.startupFailure shouldBe null
                context.getBean(DataSource::class.java).shouldBeInstanceOf<LanedDataSource>().budgets shouldBe LaneBudgets(7, 3, 1)
                val pool = context.getBean(ServiceDataSources.EVENT_LOG, HikariDataSource::class.java)
                pool.maximumPoolSize shouldBe LaneBudgets.EVENT_LOG.maximum
                pool.connection.use { it.lanes().keys shouldBe setOf("submission-svc:background") }
            }
        }
    }

    /** Takes a connection in [lane] on this executor and keeps it open until the test closes it. */
    private fun java.util.concurrent.ExecutorService.take(
        dataSource: DataSource,
        lane: PoolLane,
    ): CompletableFuture<Connection> = CompletableFuture.supplyAsync({ PoolLanes.inLane(lane) { dataSource.connection } }, this)

    /** The runtime login's connections in pg_stat_activity, by application_name. */
    private fun Connection.lanes(): Map<String, Int> = prepareStatement(
        "select application_name, count(*) from pg_stat_activity where usename = ? group by application_name",
    ).use { statement ->
        statement.setString(1, RUNTIME)
        statement.executeQuery().use { rows ->
            buildMap { while (rows.next()) put(rows.getString(1), rows.getInt(2)) }
        }
    }

    private companion object {
        const val IMAGE = "postgres:17"
        const val RUNTIME = "submission_runtime"
        const val PASSWORD = "runtime-pw"
        const val TIMEOUT_MS = 1000L
    }
}
