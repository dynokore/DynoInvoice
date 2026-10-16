package com.dynokore.platform.serviceruntime.eventloop

import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.DeadLetterScope
import com.dynokore.platform.outboxandqueue.DeadLetterStatus
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventLogPresence
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.outboxandqueue.JdbcDeadLetterTriage
import com.dynokore.platform.outboxandqueue.JdbcEventLogPurge
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.JdbcOutboxRelay
import com.dynokore.platform.outboxandqueue.JdbcRetentionPurge
import com.dynokore.platform.outboxandqueue.JdbcStalenessMonitor
import com.dynokore.platform.outboxandqueue.JdbcUnrelayedEventCheck
import com.dynokore.platform.outboxandqueue.JdbcWorkQueue
import com.dynokore.platform.outboxandqueue.Lane
import com.dynokore.platform.outboxandqueue.LaneLimits
import com.dynokore.platform.outboxandqueue.LaneRateLimiter
import com.dynokore.platform.outboxandqueue.RateLimit
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.Retention
import com.dynokore.platform.outboxandqueue.TestCatalogue
import com.dynokore.platform.serviceruntime.pool.LaneGateHolder
import com.dynokore.platform.serviceruntime.pool.PoolLane
import com.dynokore.platform.serviceruntime.pool.PoolLanes
import com.dynokore.platform.tenancykernel.LaneRefusedException
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Per recipient class of the application pool (coordinator ruling INV-Q-5243): with every BACKGROUND permit held, the
 * class's claim waits for its own lane's permits only and ends with the lane message after the pool's connection
 * timeout; it is never served from another lane. The loops are off, so the test is the only user of the pool.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RecipientLanesIT {
    private lateinit var fixture: EventLoopsIT.Fixture
    private lateinit var service: EventLoopsIT.Service
    private val context: ConfigurableApplicationContext get() = service.context

    @BeforeAll
    fun start(
        @TempDir mount: Path,
    ) {
        fixture = EventLoopsIT.Service.fixture("postgres:17")
        service =
            fixture.run(
                mount,
                "service-runtime.event-loops.enabled=false",
                "service-runtime.staleness.rules[0].consumer=submission-svc",
                "service-runtime.staleness.rules[0].event-type=${TestCatalogue.SUBMISSION_DESPATCHED}",
                "spring.datasource.hikari.connection-timeout=$TIMEOUT_MS",
                configuration = Beans::class.java,
            )
    }

    @AfterAll
    fun stop() {
        service.close()
        fixture.close()
    }

    private inline fun <reified T : Any> bean(): T = context.getBean(T::class.java)

    private val claims: Map<String, () -> Any?> by lazy {
        mapOf(
            "JdbcOutboxRelay" to { bean<JdbcOutboxRelay>().runOnce() },
            "PostgresEventLogReader" to { bean<EventLogReader>().read(EventLoopsIT.Service.TOPIC, 0, 10) },
            "LaneRateLimiter" to { bean<LaneRateLimiter>().used(Lane.BACKFILL) },
            "JdbcWorkQueue" to { bean<JdbcWorkQueue>().claimNext() },
            "JdbcDeadLetterTriage" to {
                bean<JdbcDeadLetterTriage>().list(
                    DeadLetterScope(),
                    DeadLetterStatus.OPEN,
                    1,
                )
            },
            "JdbcRetentionPurge" to { bean<JdbcRetentionPurge>().purge() },
            "JdbcEventLogPurge" to { bean<JdbcEventLogPurge>().purge() },
            "JdbcStalenessMonitor" to { bean<JdbcStalenessMonitor>().check() },
            "PostgresEventLogPresence" to {
                bean<EventLogPresence>().missing(
                    EventLoopsIT.Service.TOPIC,
                    listOf("e1"),
                )
            },
            "JdbcUnrelayedEventCheck" to { bean<JdbcUnrelayedEventCheck>().check() },
            "JdbcInboxConsumer" to { bean<JdbcInboxConsumer>().pollOnce() },
        )
    }

    @Test
    fun `the table names the eleven recipient classes of INV-Q-5243`() {
        claims.keys.size shouldBe 11
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(
        strings = [
            "JdbcOutboxRelay", "PostgresEventLogReader", "LaneRateLimiter", "JdbcWorkQueue", "JdbcDeadLetterTriage",
            "JdbcRetentionPurge", "JdbcEventLogPurge", "JdbcStalenessMonitor", "PostgresEventLogPresence",
            "JdbcUnrelayedEventCheck", "JdbcInboxConsumer",
        ],
    )
    fun `with every background permit held a claim ends with the lane message after the connection timeout`(
        recipient: String,
    ) {
        val permits = context.getBean(LaneGateHolder::class.java).permits()
        // the start-up checks and schedules of the service give their permits back first
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (permits.availablePermits(PoolLane.BACKGROUND) < permits.budgets.of(PoolLane.BACKGROUND)) {
            check(System.nanoTime() < deadline) { "the BACKGROUND lane never became idle before $recipient" }
            Thread.sleep(50)
        }
        val held = (1..permits.budgets.of(PoolLane.BACKGROUND)).map { onBackground { permits.acquire() } }
        val failure = AtomicReference<Throwable?>()
        val elapsedMs = AtomicReference<Long>()
        try {
            val worker =
                Thread {
                    val started = System.nanoTime()
                    try {
                        PoolLanes.inLane(PoolLane.BACKGROUND) { claims.getValue(recipient)() }
                    } catch (e: Throwable) {
                        failure.set(e)
                    }
                    elapsedMs.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                }
            worker.start()
            worker.join(TIMEOUT_MS * 10)
            worker.isAlive shouldBe false
        } finally {
            held.forEach { it.release() }
        }
        val refusal =
            generateSequence(
                failure.get(),
            ) { it.cause }.filterIsInstance<LaneRefusedException>().firstOrNull()
        withClue("$recipient ended with ${failure.get()}") { (refusal != null) shouldBe true }
        (refusal!!.message!!.startsWith("the BACKGROUND lane's ")) shouldBe true
        // the recipient itself asked the pool: the nearest outbox-and-queue frame under the refusal is the recipient class
        val caller =
            refusal.stackTrace
                .first {
                    it.className.startsWith(
                        "com.dynokore.platform.outboxandqueue.",
                    )
                }.className
        withClue("$recipient was refused by $caller") {
            (
                caller == "com.dynokore.platform.outboxandqueue.$recipient" ||
                    caller.startsWith("com.dynokore.platform.outboxandqueue.$recipient$")
            ) shouldBe
                true
        }
        // waited for the lane's own permits, until the pool's connection timeout
        (elapsedMs.get() >= TIMEOUT_MS - SLACK_MS) shouldBe true
        // never served from another lane, and no permit leaked
        permits.availablePermits(PoolLane.REQUEST) shouldBe permits.budgets.of(PoolLane.REQUEST)
        permits.availablePermits(PoolLane.BACKGROUND) shouldBe permits.budgets.of(PoolLane.BACKGROUND)
    }

    /**
     * The beans a service declares beside the wiring's own: no handler of its own and no consumer bean, so the wiring
     * builds the inbox consumer, the retention and event-log purges and the staleness monitor itself.
     */
    @Configuration(proxyBeanMethods = false)
    class Beans {
        @Bean
        fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()

        @Bean
        fun catalogue(): EventCatalogue = TestCatalogue.load()

        @Bean
        fun laneLimits(): LaneLimits = LaneLimits(mapOf(Lane.BACKFILL to RateLimit(1_000, Duration.ofMinutes(1))))

        @Bean
        fun retention(): Retention = Retention()

        @Bean
        fun rebuildSink(): RebuildRequiredSink = RebuildRequiredSink { }

        @Bean
        fun subscription(): ConsumerSubscription =
            ConsumerSubscription(
                "submission-svc",
                EventLoopsIT.Service.TOPIC,
                "submission",
                EventLoopsIT.ServiceBeans.RETRY,
            )

        @Bean
        fun handler(): EventHandler =
            object : EventHandler {
                override fun handle(event: Envelope): HandleOutcome = HandleOutcome.APPLIED
            }
    }

    private fun <T> onBackground(block: () -> T): T = PoolLanes.inLane(PoolLane.BACKGROUND, block)

    private companion object {
        const val TIMEOUT_MS = 1_500L
        const val SLACK_MS = 200L
    }
}
