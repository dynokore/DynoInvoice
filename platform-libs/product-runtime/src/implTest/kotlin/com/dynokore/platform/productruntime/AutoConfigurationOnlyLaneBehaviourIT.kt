package com.dynokore.platform.productruntime

import com.dynokore.platform.outboxandqueue.EventLogPresence
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.pool.PoolLane
import com.dynokore.platform.serviceruntime.pool.PoolLanes
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.web.client.RestClient
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * INV-T-3881's third composition, auto-configuration only, with the REAL ProductRuntimeConfiguration, asserted by
 * behaviour through the library's public API against PostgreSQL (INV-T-4747, owner ruling INV-Q-4798 (E): "4798 E",
 * integration tests do not assert a service's internal beans): concurrent transactions through TransactionRunner show
 * each lane's connection limit, and background work never takes a request-lane connection. The test names no pool
 * type and no java.sql or javax.sql type.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AutoConfigurationOnlyLaneBehaviourIT {
    @TempDir
    lateinit var mountRoot: Path

    private val postgres: PostgreSQLContainer<*> = postgres()

    @AfterAll
    fun stop() = postgres.stop()

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(DefaultSchemaConfiguration::class)
    class AutoConfigurationOnlyApplication

    private fun postgres(): PostgreSQLContainer<*> =
        PostgreSQLContainer(DockerImageName.parse(IMAGE)).also { it.start() }

    /** The service of AutoConfigurationOnlyOrderTest over the container; its login is mounted in SecretStore. */
    private fun service(vararg properties: String): ApplicationContextRunner {
        Files.writeString(mountRoot.resolve(LOGIN_REF), "${postgres.password}\n")
        return ApplicationContextRunner()
            .withUserConfiguration(AutoConfigurationOnlyApplication::class.java)
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
            .withBean("internalRestClientBuilder", RestClient.Builder::class.java, { RestClient.builder() })
            .withPropertyValues(
                "gst.service=submission-svc",
                "gst.instance-id=submission-svc-0",
                "service-runtime.workload-identity.audience=submission-svc",
                "service-runtime.modules.schema=submission",
                "spring.datasource.url=${postgres.jdbcUrl}",
                "spring.datasource.username=${postgres.username}",
                "spring.datasource.hikari.connection-timeout=$TIMEOUT_MS",
                "gst.bindings.secret-store.mount-root=$mountRoot",
                "service-runtime.datasource.credential=$LOGIN_REF",
                "service-runtime.pool.profile=minimal",
                *properties,
            )
    }

    @Test
    fun `one application pool per instance and no role DataSource, the event log collapses into it on one instance`() {
        for (eventLogUrl in listOf(null, postgres.jdbcUrl)) {
            val properties = listOfNotNull(eventLogUrl?.let { "gst.db.event-log-url=$it" }).toTypedArray()
            service(*properties).run { context ->
                context.startupFailure shouldBe null
                context.getBean(TenantScope::class.java).workloadPrincipal shouldBe "svc:submission-svc"
                assertLanesAtTheirLimits(
                    context.getBean(TransactionRunner::class.java),
                    context.getBean(TenantContext::class.java),
                )
                // the event log runs on the application pool (nothing separate is created): a read of the event log
                // opens no connection beyond the sum of the lane budgets 4 + 2 + 0 on the one database
                useEventLog(context.getBean(EventLogPresence::class.java))
                (sessionsOf(postgres) <= MINIMAL_POOL_MAXIMUM) shouldBe true
            }
        }
    }

    @Test
    fun `a separate event-log instance gets its own pool of at most 3, minimumIdle 1, app pool stays sole candidate`() {
        postgres().use { eventLog ->
            val ha = service("gst.db.event-log-url=${eventLog.jdbcUrl}")
            ha.run { context ->
                context.startupFailure shouldBe null
                // the application transactions run against the application database, on the application lanes
                assertLanesAtTheirLimits(
                    context.getBean(TransactionRunner::class.java),
                    context.getBean(TenantContext::class.java),
                )
                (sessionsOf(postgres) <= MINIMAL_POOL_MAXIMUM) shouldBe true
                // the event-log pool is created lazily and no application transaction ran on it
                sessionsOf(eventLog) shouldBe 0
                // the event-log port reaches the event-log database through a pool of its own, minimumIdle 1; the
                // application database gains no connection
                useEventLog(context.getBean(EventLogPresence::class.java))
                eventually { sessionsOf(eventLog) } shouldBe 1
                (sessionsOf(postgres) <= MINIMAL_POOL_MAXIMUM) shouldBe true
            }
            ha.withPropertyValues("service-runtime.event-log-pool.minimum-idle=2").run { context ->
                context.startupFailure shouldBe null
                useEventLog(context.getBean(EventLogPresence::class.java))
                eventually { sessionsOf(eventLog) } shouldBe 2
            }
            ha.withPropertyValues("service-runtime.event-log-pool.minimum-idle=4").run { context ->
                generateSequence(context.startupFailure.shouldNotBeNull()) { it.cause }.any {
                    it.message.orEmpty().contains("minimum-idle must be 0..3")
                } shouldBe true
            }
        }
    }

    /**
     * Holds transactions open on other threads and shows what each lane lets through: the background lane serves its 2
     * and refuses a third; the request lane serves its 4 at once, with the background lane saturated, and refuses a
     * fifth; the reprocessing lane (budget 0, the service declares no reprocessing) is refused and never served from
     * another lane. A refused lane names itself in the message; none of the lanes borrows from another.
     */
    private fun assertLanesAtTheirLimits(
        transactions: TransactionRunner,
        tenants: TenantContext,
    ) {
        val tenant = TenantId(UUID.randomUUID())
        val threads = Executors.newFixedThreadPool(MINIMAL_POOL_MAXIMUM + 3)
        val release = CountDownLatch(1)

        fun inLane(
            lane: PoolLane,
            entered: CountDownLatch?,
        ): CompletableFuture<Int> =
            CompletableFuture.supplyAsync({
                PoolLanes.inLane(lane) {
                    tenants.bind(tenant, "user-a") {
                        transactions.inTransaction {
                            entered?.countDown()
                            release.await(HOLD_SECONDS, TimeUnit.SECONDS)
                            1
                        }
                    }
                }
            }, threads)

        fun refusal(lane: PoolLane): String =
            shouldThrow<ExecutionException> { inLane(lane, null).get(HOLD_SECONDS, TimeUnit.SECONDS) }
                .let {
                    generateSequence<Throwable>(
                        it,
                    ) { t -> t.cause }.joinToString(" | ") { t -> t.message.orEmpty() }
                }

        try {
            val backgroundEntered = CountDownLatch(BACKGROUND_PERMITS)
            val background = (1..BACKGROUND_PERMITS).map { inLane(PoolLane.BACKGROUND, backgroundEntered) }
            backgroundEntered.await(HOLD_SECONDS, TimeUnit.SECONDS) shouldBe true
            refusal(PoolLane.BACKGROUND) shouldContain "the BACKGROUND lane's $BACKGROUND_PERMITS permits stayed in use"

            // with the background lane saturated every request permit is still served at once
            val requestEntered = CountDownLatch(REQUEST_PERMITS)
            val requests = (1..REQUEST_PERMITS).map { inLane(PoolLane.REQUEST, requestEntered) }
            requestEntered.await(TIMEOUT_MS * 2, TimeUnit.MILLISECONDS) shouldBe true
            refusal(PoolLane.REQUEST) shouldContain "the REQUEST lane's $REQUEST_PERMITS permits stayed in use"
            refusal(PoolLane.REPROCESSING) shouldContain "the REPROCESSING lane has no permits (budget 0)"

            release.countDown()
            (background + requests).map { it.get(HOLD_SECONDS, TimeUnit.SECONDS) } shouldHaveSize
                BACKGROUND_PERMITS + REQUEST_PERMITS
        } finally {
            release.countDown()
            threads.shutdownNow()
        }
    }

    /**
     * Calls the event-log port. The test databases hold no event-log schema, so the call fails in the database after
     * it has taken a connection from the pool that serves the event log; the failure is the expected outcome.
     */
    private fun useEventLog(presence: EventLogPresence) {
        runCatching { presence.missing("probe-topic", listOf("probe-event")) }.exceptionOrNull().shouldNotBeNull()
    }

    /** The value of [read] once it has stopped changing for a second, or after [HOLD_SECONDS]. */
    private fun eventually(read: () -> Int): Int {
        var last = read()
        repeat(HOLD_SECONDS.toInt()) {
            Thread.sleep(1000)
            val now = read()
            if (now == last) return now
            last = now
        }
        return last
    }

    /** The sessions of the test login on [database], as the database shows them (psql in the container). */
    private fun sessionsOf(database: PostgreSQLContainer<*>): Int =
        database
            .execInContainer(
                "psql",
                "-U",
                database.username,
                "-d",
                database.databaseName,
                "-tAc",
                "select count(*) from pg_stat_activity where usename = '${database.username}' " +
                    "and application_name like 'submission-svc%' and pid <> pg_backend_pid()",
            ).stdout
            .trim()
            .toInt()

    private companion object {
        const val IMAGE = "postgres:17"
        const val LOGIN_REF = "db-test-login"
        const val TIMEOUT_MS = 1000L
        const val HOLD_SECONDS = 20L
        const val REQUEST_PERMITS = 4
        const val BACKGROUND_PERMITS = 2
        const val MINIMAL_POOL_MAXIMUM = REQUEST_PERMITS + BACKGROUND_PERMITS
    }
}
