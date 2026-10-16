package com.dynokore.platform.serviceruntime.eventloop

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ConsumerRetryPolicy
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.EventLogSchema
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.LaneLimits
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxPostgresFixture
import com.dynokore.platform.outboxandqueue.OutboxRelay
import com.dynokore.platform.outboxandqueue.OutboxSchema
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.outboxandqueue.QueueSchema
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.ReplayDelivery
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.outboxandqueue.TestCatalogue
import com.dynokore.platform.serviceruntime.composition.OutboxAndQueueWiring
import com.dynokore.platform.serviceruntime.datasource.TEST_LOGIN_REF
import com.dynokore.platform.serviceruntime.datasource.Tpl18JdbcApplication
import com.dynokore.platform.serviceruntime.pool.EventLogGate
import com.dynokore.platform.serviceruntime.pool.LaneGateHolder
import com.dynokore.platform.serviceruntime.pool.PermitObserver
import com.dynokore.platform.serviceruntime.pool.PoolLane
import com.dynokore.platform.serviceruntime.testing.HeldTransaction
import com.dynokore.platform.serviceruntime.testing.TestDatabase
import com.dynokore.platform.tenancyjooq.TableClass
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancyjooq.TenancyTables
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.impl.DefaultConfiguration
import org.jooq.impl.SQLDataType
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * The event loops in a service composed as TPL-18 writes it, started by SpringApplication so that ApplicationReadyEvent
 * fires (INV-T-3938): a producer and two subscribers on one pool, on PostgreSQL 17 and 18. Nothing here calls
 * `runOnce` or `pollOnce`.
 */
class EventLoopsIT {
    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `an event appended through the OutboxWriter reaches the event log and is applied once by the inbox`(
        image: String,
        @TempDir mount: Path,
    ) {
        Service.fixture(image).use { fx ->
            fx.run(mount).use { svc ->
                val loops = svc.context.getBean(EventLoops::class.java)
                loops.loops().map { "${it.kind.label}:${it.name}" } shouldContainExactlyInAnyOrder
                    listOf(
                        "relay:outboxRelay",
                        "consumer:despatchConsumer",
                        "consumer:secondConsumer",
                        "replay:replayDelivery",
                    )
                val event = svc.append(1).single()
                // within 10 s, applied once by each subscriber (INV-Q-4850 probe a)
                eventually("each subscriber to apply the event", Duration.ofSeconds(10)) {
                    fx.count(APPLIED) >= 2
                }
                fx.count(APPLIED, col("consumer").eq("submission-svc-b")) shouldBe 1
                val topic = "eventlog.${EventLogSchema.tableName(Service.TOPIC)}"
                fx.count(topic, col("event_id").eq(event.id.value)) shouldBe 1
                fx.count(OUTBOX, col("id").eq(event.id.value), col("published_at").isNotNull) shouldBe 1
                // once: one row per consumer, one inbox row per consumer, one event-log record
                fx.count(APPLIED, col("consumer").eq("submission-svc")) shouldBe 1
                fx.count(INBOX, col("consumer").eq("submission-svc")) shouldBe 1
                fx.count("eventlog.${EventLogSchema.tableName(Service.TOPIC)}") shouldBe 1
                fx.count(OUTBOX, col("published_at").isNotNull) shouldBe 1
                // the consumer, not the loop, bound the row's tenant and the service's workload principal
                svc.handler.bound.single() shouldBe Triple(event.id.value, Service.TENANT, "svc:submission-svc")
                // every loop reports a last successful call, the service is ready
                eventually("every loop to report a call") { loops.loops().all { it.lastSuccess() != null } }
                svc.context
                    .getBean(MeterRegistry::class.java)
                    .get(EventLoops.LAST_SUCCESS)
                    .tag("kind", "relay")
                    .gauge()
                    .value()
                    .let { it > 0.0 } shouldBe true
                EventLoopHealthIndicator { loops }.health().status.code shouldBe "UP"
                // later events too, with the loops running (no call from the test)
                svc.append(5)
                eventually("five more events") {
                    fx.count(APPLIED, col("consumer").eq("submission-svc")) == 6L
                }
            }
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `with the loops switched off nothing moves, and the beans can be called by hand`(
        image: String,
        @TempDir mount: Path,
    ) {
        Service.fixture(image).use { fx ->
            fx.run(mount, "service-runtime.event-loops.enabled=false").use { svc ->
                svc.context.getBeansOfType(EventLoops::class.java).size shouldBe 0
                svc.append(1)
                Thread.sleep(3_500)
                fx.count(OUTBOX, col("published_at").isNotNull) shouldBe 0
                fx.count("eventlog.${EventLogSchema.tableName(Service.TOPIC)}") shouldBe 0
                svc.context
                    .getBean(OutboxRelay::class.java)
                    .runOnce()
                    .published shouldBe 1
                svc.context
                    .getBean("despatchConsumer", JdbcInboxConsumer::class.java)
                    .pollOnce()
                    .applied shouldBe 1
            }
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `two application contexts on one database publish each row once, in seq order, and apply each event once`(
        image: String,
        @TempDir mount: Path,
    ) {
        Service.fixture(image).use { fx ->
            fx.run(mount).use { first ->
                fx.run(mount, instance = "submission-svc-1").use { second ->
                    val appended = (1..4).flatMap { listOf(first, second)[it % 2].append(10) }
                    eventually("all events applied", Duration.ofSeconds(60)) {
                        fx.count(APPLIED, col("consumer").eq("submission-svc")) == 40L
                    }
                    val table = "eventlog.${EventLogSchema.tableName(Service.TOPIC)}"
                    fx.count(table) shouldBe 40
                    fx.countDistinct(table, "event_id") shouldBe 40
                    fx.count(INBOX, col("consumer").eq("submission-svc")) shouldBe 40
                    // published in outbox seq order: log order is seq order
                    val logOrder = fx.strings(table, "event_id", "log_offset")
                    val seqOrder = fx.strings(OUTBOX, "id", "seq")
                    logOrder shouldContainExactly seqOrder
                    logOrder.toSet() shouldBe appended.map { it.id.value }.toSet()
                    first.context
                        .getBean(EventLoops::class.java)
                        .loops()
                        .none { it.ended() != null } shouldBe true
                }
            }
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a handler that throws is retried then dead-lettered, and the loop keeps running`(
        image: String,
        @TempDir mount: Path,
    ) {
        Service.fixture(image).use { fx ->
            fx.run(mount).use { svc ->
                svc.handler.failing +=
                    svc
                        .append(1)
                        .single()
                        .id.value
                eventually("the dead letter", Duration.ofSeconds(30)) {
                    fx.count(DEAD_LETTER, col("consumer").eq("submission-svc")) == 1L
                }
                fx.count(APPLIED, col("consumer").eq("submission-svc")) shouldBe 0
                svc.handler.attempts.get() shouldBe 3 // maxAttempts of the fixture subscription
                // the loop went on: the next event is applied
                svc.append(1)
                eventually("the next event to be applied") {
                    fx.count(APPLIED, col("consumer").eq("submission-svc")) == 1L
                }
                val consumerLoop =
                    svc.context
                        .getBean(EventLoops::class.java)
                        .loops()
                        .first { it.name == "despatchConsumer" }
                consumerLoop.isAlive() shouldBe true
                consumerLoop.ended() shouldBe null
            }
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `shutdown stops the loops after the call in hand and before the pool closes, logging no error`(
        image: String,
        @TempDir mount: Path,
    ) {
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        Service.fixture(image).use { fx ->
            val svc = fx.run(mount)
            val loops = svc.context.getBean(EventLoops::class.java)
            val pool = svc.context.getBean("dataSource", DataSource::class.java)
            svc.handler.hold = CountDownLatch(1)
            svc.append(1)
            svc.handler.entered.await(20, TimeUnit.SECONDS) shouldBe true
            // the handler is in its transaction (a pool connection taken); close while it runs
            val release =
                Thread.ofVirtual().start {
                    Thread.sleep(700)
                    svc.handler.hold!!.countDown()
                }
            root.addAppender(appender)
            try {
                svc.close()
            } finally {
                root.detachAppender(appender)
            }
            release.join()
            loops.loops().none { it.isAlive() } shouldBe true
            loops.isRunning shouldBe false
            // the call in hand finished and committed before the pool closed
            fx.count(APPLIED, col("consumer").eq("submission-svc")) shouldBe 1
            pool.unwrap(com.zaxxer.hikari.HikariDataSource::class.java).isClosed shouldBe true
        }
        appender.list
            .filter { it.level.isGreaterOrEqual(Level.WARN) }
            .map { it.formattedMessage }
            .shouldBeEmpty()
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `with the background lane saturated by the loops, requests still get connections`(
        image: String,
        @TempDir mount: Path,
    ) {
        Service.fixture(image).use { fx ->
            // minimal profile, no lane property: 4 / 2 / 0
            fx.run(mount).use { svc ->
                val pool = svc.context.getBean("dataSource", DataSource::class.java)
                val laned = svc.context.getBean(LaneGateHolder::class.java).permits()
                svc.handler.hold = CountDownLatch(1)
                svc.append(1)
                // one consumer sits in its handler transaction (one background permit)
                svc.handler.entered.await(20, TimeUnit.SECONDS) shouldBe true
                // the next event: the relay's claim connection takes the other permit, then waits for the row's one
                svc.append(1)
                // sustained, not a passing moment of another loop's claim
                var zeros = 0
                eventually("the lane saturated") {
                    zeros = if (laned.availablePermits(PoolLane.BACKGROUND) == 0) zeros + 1 else 0
                    zeros >= 10
                }
                // a request-lane thread (no lane entered) gets its connections at once, twice over
                val started = System.nanoTime()
                TestDatabase(fx.jdbcUrl(), emptyMap()).use { db ->
                    val access = db.over(OutboxAndQueueWiring.handOff(pool, laned))
                    HeldTransaction(access).use { a ->
                        HeldTransaction(access).use { b ->
                            a.user()
                            b.user()
                        }
                    }
                }
                (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_500) shouldBe true
                laned.availablePermits(PoolLane.REQUEST) shouldBe 4
                laned.availablePermits(PoolLane.BACKGROUND) shouldBe 0
                svc.handler.hold!!.countDown()
                eventually("both consumers to apply both events", Duration.ofSeconds(60)) {
                    fx.count(APPLIED) == 4L
                }
            }
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a relay holds at most 2 connections of the application pool, 1 more of the event-log pool in the HA form`(
        image: String,
        @TempDir mount: Path,
    ) {
        val relay = "event-loop-relay-outboxRelay"
        val consumer = "event-loop-consumer-despatchConsumer"
        Service.fixture(image).use { fx ->
            fx.run(mount).use { svc ->
                val peaks = svc.context.getBean(PeakCounting::class.java)
                peaks.reset()
                svc.append(3)
                eventually("the events to be applied", Duration.ofSeconds(10)) {
                    fx.count(APPLIED, col("consumer").eq("submission-svc")) == 3L
                }
                withClue(peaks.dump()) {
                    peaks.peak("dataSource", relay) shouldBe 2
                    peaks.peak("dataSource", consumer) shouldBe 1
                    peaks.peak("eventLogDataSource", relay) shouldBe 0
                }
            }
        }
        Service.fixture(image).use { fx ->
            fx.run(mount, "gst.db.event-log-url=${fx.haEventLogUrl()}").use { svc ->
                val peaks = svc.context.getBean(PeakCounting::class.java)
                peaks.reset()
                svc.append(3)
                eventually("the events to be applied", Duration.ofSeconds(10)) {
                    fx.count(APPLIED, col("consumer").eq("submission-svc")) == 3L
                }
                withClue(peaks.dump()) {
                    peaks.peak("dataSource", relay) shouldBe 2
                    peaks.peak("eventLogDataSource", relay) shouldBe 1
                }
            }
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a publish that fails to commit leaves no event-log row on the shared pool, one in the HA form`(
        image: String,
        @TempDir mount: Path,
    ) {
        for (ha in listOf(false, true)) {
            Service.fixture(image).use { fx ->
                val extra = mutableListOf("service-runtime.event-loops.enabled=false")
                if (ha) extra += "gst.db.event-log-url=${fx.haEventLogUrl()}"
                fx.run(mount, *extra.toTypedArray()).use { svc ->
                    val id =
                        svc
                            .append(1)
                            .single()
                            .id.value
                    val topic = "eventlog.${EventLogSchema.tableName(Service.TOPIC)}"
                    val rows = { fx.countIn(ha, topic, col("event_id").eq(id)) }
                    val relay = svc.context.getBean(OutboxRelay::class.java)
                    // the container's trigger on the outbox refuses a publish while the switch is on
                    fx.blockPublishing(true)
                    runCatching { relay.runOnce() }
                    val unpublished = { fx.count(OUTBOX, col("id").eq(id), col("published_at").isNull) }
                    rows() shouldBe if (ha) 1 else 0
                    unpublished() shouldBe 1
                    fx.blockPublishing(false)
                    relay.runOnce().published shouldBe 1
                    rows() shouldBe if (ha) 2 else 1
                    unpublished() shouldBe 0
                }
            }
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a relay with a single background permit publishes nothing, and the failure is visible`(
        image: String,
        @TempDir mount: Path,
    ) {
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        try {
            Service.fixture(image).use { fx ->
                val extra =
                    arrayOf(
                        "service-runtime.pool.lanes.background=1",
                        "spring.datasource.hikari.connection-timeout=2000",
                    )
                fx.run(mount, *extra).use { svc ->
                    root.addAppender(appender) // after start-up, which re-initialises logging
                    svc.append(1)
                    val gauge = {
                        svc.context
                            .getBean(MeterRegistry::class.java)
                            .get(EventLoops.LAST_SUCCESS)
                            .tag("loop", "outboxRelay")
                            .gauge()
                            .value()
                    }
                    // past the first failed call
                    eventually("the first failed outboxRelay call") {
                        // snapshot under the appender lock (doAppend is synchronized): the relay thread appends concurrently
                        synchronized(appender) { appender.list.toList() }.any {
                            it.level == Level.WARN && it.formattedMessage.contains("loop=outboxRelay")
                        }
                    }
                    val before = gauge()
                    Thread.sleep(10_000)
                    fx.count(OUTBOX, col("published_at").isNotNull) shouldBe 0
                    fx.count("eventlog.${EventLogSchema.tableName(Service.TOPIC)}") shouldBe 0
                    // never advanced: NaN (no success yet) or unchanged
                    ((before.isNaN() && gauge().isNaN()) || gauge() == before) shouldBe true
                    val failures =
                        appender.list
                            .filter { it.level == Level.WARN && it.formattedMessage.contains("loop=outboxRelay") }
                            .map { it.formattedMessage }
                    failures.isNotEmpty() shouldBe true
                    failures.all {
                        it.contains(
                            "sql_state=",
                        ) && it.contains("lane_message=the BACKGROUND lane")
                    } shouldBe
                        true
                    val loop =
                        svc.context
                            .getBean(EventLoops::class.java)
                            .loops()
                            .first { it.name == "outboxRelay" }
                    loop.isAlive() shouldBe true
                }
            }
        } finally {
            root.detachAppender(appender)
        }
    }

    /** The recording handler of one consumer; `failing` ids throw, `hold` blocks inside the transaction. */
    class RecordingHandler(
        private val consumer: String,
        private val dsl: DSLContext,
        private val tenants: TenantContext,
    ) : EventHandler {
        val failing: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        val bound: MutableList<Triple<String, UUID, String?>> = Collections.synchronizedList(mutableListOf())
        val attempts =
            java.util.concurrent.atomic
                .AtomicInteger()
        val entered = CountDownLatch(1)

        @Volatile
        var hold: CountDownLatch? = null

        override fun handle(event: Envelope): HandleOutcome {
            attempts.incrementAndGet()
            entered.countDown()
            if (event.id.value in failing) throw IllegalStateException("handler failure")
            bound += Triple(event.id.value, tenants.current().value, tenants.currentActor())
            dsl
                .insertInto(DSL.table(DSL.name("submission", "applied")))
                .columns(col("consumer"), col("event_id"), col("tenant_id"))
                .values(consumer, event.id.value, tenants.current().value)
                .execute()
            hold?.await(30, TimeUnit.SECONDS)
            return HandleOutcome.APPLIED
        }
    }

    /** The service's own beans, as its bootstrap module declares them. */
    @Configuration(proxyBeanMethods = false)
    class ServiceBeans {
        @Bean
        fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()

        @Bean
        fun catalogue(): EventCatalogue = TestCatalogue.load()

        @Bean
        fun laneLimits(): LaneLimits = LaneLimits()

        @Bean
        fun rebuildSink(): RebuildRequiredSink = RebuildRequiredSink { }

        @Bean
        fun handler(
            dsl: AppliedDsl,
            tenants: TenantContext,
        ): RecordingHandler = RecordingHandler("submission-svc", dsl.dsl, tenants)

        @Bean
        fun secondHandler(
            dsl: AppliedDsl,
            tenants: TenantContext,
        ): RecordingHandler = RecordingHandler("submission-svc-b", dsl.dsl, tenants)

        /** The handlers' own DSLContext on the service's transaction runner, with `submission.applied` declared. */
        @Bean
        fun appliedDsl(
            tenants: TenantContext,
            runner: TransactionRunner,
        ): AppliedDsl =
            AppliedDsl(
                TenancyJooq
                    .configure(
                        DefaultConfiguration().set(SQLDialect.POSTGRES),
                        TenancyTables.declared(mapOf(DSL.name("submission", "applied") to TableClass.TENANT)),
                        tenants,
                        runner,
                    ).dsl(),
            )

        /** A service-declared consumer per subscription; the first is primary for the replay delivery wiring. */
        @Bean
        @Primary
        @Suppress("LongParameterList")
        fun despatchConsumer(
            catalogue: EventCatalogue,
            reader: EventLogReader,
            dataSource: DataSource,
            gate: LaneGateHolder,
            scope: TenantScope,
            rebuildRequired: RebuildRequiredSink,
            clock: Clock,
            handler: RecordingHandler,
        ): JdbcInboxConsumer =
            JdbcInboxConsumer(
                ConsumerSubscription("submission-svc", Service.TOPIC, "submission", RETRY),
                catalogue,
                reader,
                OutboxAndQueueWiring.handOff(dataSource, gate.takeIf { it.active }),
                scope,
                handler,
                rebuildRequired,
                clock,
            )

        @Bean
        @Suppress("LongParameterList")
        fun secondConsumer(
            catalogue: EventCatalogue,
            reader: EventLogReader,
            dataSource: DataSource,
            gate: LaneGateHolder,
            scope: TenantScope,
            rebuildRequired: RebuildRequiredSink,
            clock: Clock,
            @org.springframework.beans.factory.annotation.Qualifier("secondHandler") handler: RecordingHandler,
        ): JdbcInboxConsumer =
            JdbcInboxConsumer(
                ConsumerSubscription("submission-svc-b", Service.TOPIC, "submission", RETRY),
                catalogue,
                reader,
                OutboxAndQueueWiring.handOff(dataSource, gate.takeIf { it.active }),
                scope,
                handler,
                rebuildRequired,
                clock,
            )

        /** Observes the permits of the application pool's lane gate and of the HA event-log pool's gate (INV-Q-5251). */
        @Bean
        fun peakCounting(
            gate: LaneGateHolder,
            eventLogGate: EventLogGate,
        ): PeakCounting =
            PeakCounting().also {
                gate.observe(it.observer("dataSource"))
                eventLogGate.observe(it.observer("eventLogDataSource"))
            }

        companion object {
            val RETRY =
                ConsumerRetryPolicy(
                    maxAttempts = 3,
                    initialDelay = Duration.ofMillis(100),
                    maxDelay = Duration.ofMillis(200),
                )
        }
    }

    /** One running service over the fixture's database. */
    class Service(
        val context: ConfigurableApplicationContext,
        private val fixture: OutboxPostgresFixture,
    ) : AutoCloseable {
        val handler: RecordingHandler get() = context.getBean("handler", RecordingHandler::class.java)
        val secondHandler: RecordingHandler get() = context.getBean("secondHandler", RecordingHandler::class.java)

        /** Appends [count] events through the OutboxWriter, one transaction each, as a command would. */
        fun append(count: Int): List<Envelope> {
            val tenants = context.getBean(TenantContext::class.java)
            val transactions = context.getBean(TransactionRunner::class.java)
            val writer = context.getBean(OutboxWriter::class.java)
            return (1..count).map {
                val doc = UUID.randomUUID()
                val context =
                    EventContext(
                        tenant = TenantId(TENANT),
                        entity = EntityRef("entity-1"),
                        docUuid = DocUuid(doc),
                        transactionDate = LocalDate.of(2026, 9, 30),
                        aggregateKey = doc.toString(),
                        aggregateVersion = null,
                        correlation = CorrelationId("corr-$doc"),
                        causation = CausationId("cmd-1"),
                    )
                tenants
                    .bind(TenantId(TENANT), "svc:submission-svc") {
                        transactions.inTransaction {
                            val event = OutboundEvent(TestCatalogue.SUBMISSION_DESPATCHED, """{"version":1}""")
                            writer.append(listOf(event), context)
                        }
                    }.single()
            }
        }

        override fun close() {
            context.close()
        }

        companion object {
            const val TENANT_TEXT = "6f1c1a52-0000-4000-8000-000000000001"
            val TENANT: UUID = UUID.fromString(TENANT_TEXT)
            const val TOPIC = "gst.submission.submission-despatched.v1"

            fun fixture(image: String) = Fixture(image)
        }
    }

    class Fixture(
        image: String,
    ) : AutoCloseable {
        private val catalogue = TestCatalogue.load()
        private val fx =
            OutboxPostgresFixture(
                image,
                OutboxPostgresFixture.serviceInitSql(
                    "submission",
                    "create table submission.applied (consumer text not null, event_id text not null, " +
                        "tenant_id uuid not null, primary key (consumer, event_id));" +
                        "grant select, insert on submission.applied to ${OutboxPostgresFixture.APP};" +
                        QueueSchema.ddl("submission", OutboxPostgresFixture.APP, OutboxPostgresFixture.SYS),
                ) + OutboxPostgresFixture.eventLogInitSql(catalogue) + PUBLISH_SWITCH_INIT_SQL +
                    OutboxPostgresFixture.haEventLogInitSql(catalogue),
            )

        /** The superuser's access to the service database and to the HA event-log database (no tenant predicates). */
        private val adminDb =
            TestDatabase(
                fx.jdbcUrl(),
                (
                    listOf(
                        APPLIED,
                        OUTBOX,
                        INBOX,
                        DEAD_LETTER,
                        "public.publish_switch",
                        "eventlog.${EventLogSchema.tableName(Service.TOPIC)}",
                    )
                ).associateWith { TableClass.PLATFORM_SCOPED },
            )
        private val admin = adminDb.login(fx.adminUser(), fx.adminPassword())
        private val haDb =
            TestDatabase(
                fx.haEventLogUrl(),
                mapOf("eventlog.${EventLogSchema.tableName(Service.TOPIC)}" to TableClass.PLATFORM_SCOPED),
            )
        private val haAdmin = haDb.login(fx.adminUser(), fx.adminPassword())

        /** The container's URL, for a test that builds its own access. */
        fun jdbcUrl(): String = fx.jdbcUrl()

        /** The URL of the event log in the second database (HA form), provisioned with the container. */
        fun haEventLogUrl(): String = fx.haEventLogUrl()

        /** Starts the service; [extra] are `key=value` properties. */
        fun run(
            mount: Path,
            vararg extra: String,
            instance: String = "submission-svc-0",
            configuration: Class<*> = ServiceBeans::class.java,
        ): Service {
            val url = fx.jdbcUrl().substringBefore("?")
            Files.writeString(mount.resolve(TEST_LOGIN_REF), "outbox_app_pw\n")
            val context =
                SpringApplicationBuilder(Tpl18JdbcApplication::class.java, configuration)
                    .web(WebApplicationType.NONE)
                    .properties(
                        "spring.datasource.url=$url",
                        "spring.datasource.username=${OutboxPostgresFixture.APP}",
                        "gst.bindings.secret-store.mount-root=$mount",
                        "service-runtime.datasource.credential=$TEST_LOGIN_REF",
                        "service-runtime.workload-identity.audience=submission-svc",
                        "gst.service=submission-svc",
                        "gst.instance-id=$instance",
                        "service-runtime.modules.schema=submission",
                        "service-runtime.pool.profile=minimal",
                        "service-runtime.idempotency.schema=submission",
                        "spring.flyway.enabled=false",
                        "spring.main.banner-mode=off",
                        "spring.jmx.enabled=false",
                        *extra,
                    ).run()
            return Service(context, fx)
        }

        /** Rows of [table] (`schema.table`) in the service database that meet every one of [where]. */
        fun count(
            table: String,
            vararg where: Condition,
        ): Long = countOn(admin, table, *where)

        /** As [count], in the HA event-log database when [ha], else in the service database. */
        fun countIn(
            ha: Boolean,
            table: String,
            vararg where: Condition,
        ): Long = countOn(if (ha) haAdmin else admin, table, *where)

        private fun countOn(
            access: TestDatabase.Access,
            table: String,
            vararg where: Condition,
        ): Long =
            access
                .asPlatform { fetchCount(DSL.table(DSL.name(table.split('.'))), DSL.and(*where)) }
                .toLong()

        fun countDistinct(
            table: String,
            column: String,
        ): Long =
            admin
                .asPlatform {
                    select(
                        DSL.countDistinct(col(column)),
                    ).from(DSL.table(DSL.name(table.split('.')))).fetchSingle().value1()
                }.toLong()

        /** Switches the container's trigger that refuses a publish of an outbox row on or off. */
        fun blockPublishing(blocked: Boolean) {
            admin.asPlatform {
                update(DSL.table(DSL.name("public", "publish_switch"))).set(col("blocked"), blocked).execute()
            }
        }

        /** Column [column] of every row of [table] in the service database, ordered by [orderBy]. */
        fun strings(
            table: String,
            column: String,
            orderBy: String,
        ): List<String> =
            admin.asPlatform {
                select(DSL.field(DSL.name(column), SQLDataType.VARCHAR))
                    .from(DSL.table(DSL.name(table.split('.'))))
                    .orderBy(col(orderBy))
                    .fetch(DSL.field(DSL.name(column), SQLDataType.VARCHAR))
            }

        override fun close() {
            haDb.close()
            adminDb.close()
            fx.close()
        }
    }

    private fun eventually(
        what: String,
        timeout: Duration = Duration.ofSeconds(20),
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out waiting for $what" }
            Thread.sleep(POLL_MS)
        }
    }

    private companion object {
        const val POLL_MS = 100L
    }
}

/** A holder type, so the handlers' DSLContext is not a second DSLContext bean of the context. */
class AppliedDsl(
    val dsl: DSLContext,
)

/**
 * Counts, per gate and per thread name, the permits held at once and keeps the peak (coordinator ruling INV-Q-5251): an
 * observer of the application pool's lane gate and of the HA event-log pool's gate. A permit counts for the thread that
 * took it until it is given back (by any thread).
 */
class PeakCounting {
    private val open = ConcurrentHashMap<String, AtomicInteger>()
    private val peaks = ConcurrentHashMap<String, AtomicInteger>()

    /** The observer of the gate of [pool] ("dataSource" or "eventLogDataSource"). */
    fun observer(pool: String): PermitObserver =
        object : PermitObserver {
            override fun acquired(threadName: String) {
                val key = "$pool|$threadName"
                val now = open.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet()
                peaks.computeIfAbsent(key) { AtomicInteger() }.accumulateAndGet(now, ::maxOf)
            }

            override fun released(threadName: String) {
                open.computeIfAbsent("$pool|$threadName") { AtomicInteger() }.decrementAndGet()
            }
        }

    /** The peak of [pool] for threads whose name is [thread]. */
    fun peak(
        pool: String,
        thread: String,
    ): Int = peaks["$pool|$thread"]?.get() ?: 0

    /** Every peak seen, for a failure message. */
    fun dump(): String = peaks.entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value.get()}" }

    /** Forgets the peaks; the permits held now stay counted, so a permit taken before and given back after balances. */
    fun reset() {
        peaks.clear()
        open.forEach { (key, held) -> peaks.computeIfAbsent(key) { AtomicInteger() }.set(held.get().coerceAtLeast(0)) }
    }
}

private const val APPLIED = "submission.applied"
private const val OUTBOX = "submission.outbox"
private const val INBOX = "submission.inbox"
private const val DEAD_LETTER = "submission.event_dead_letter"

private fun col(name: String): Field<Any> = DSL.field(DSL.name(name))

/**
 * Container provisioning for the publish-failure test: a switch row and a trigger that refuses to mark an outbox row
 * published while the switch is on (the test flips the row; it issues no DDL).
 */
private val PUBLISH_SWITCH_INIT_SQL =
    """
    RESET ROLE;
    create table public.publish_switch (blocked boolean not null);
    insert into public.publish_switch values (false);
    grant select on public.publish_switch to ${OutboxPostgresFixture.APP};
    create function public.block_publish() returns trigger language plpgsql as ${'$'}${'$'}
    begin
        if new.published_at is not null and old.published_at is null
           and (select blocked from public.publish_switch) then
            raise exception 'blocked';
        end if;
        return new;
    end ${'$'}${'$'};
    SET ROLE ${OutboxPostgresFixture.OWNER};
    create trigger block_publish before update on submission.outbox for each row
        execute function public.block_publish();

    """.trimIndent()
