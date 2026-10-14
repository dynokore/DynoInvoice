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
import com.dynokore.platform.serviceruntime.datasource.TEST_LOGIN_REF
import com.dynokore.platform.serviceruntime.datasource.Tpl18JdbcApplication
import com.dynokore.platform.serviceruntime.pool.LanedDataSource
import com.dynokore.platform.serviceruntime.pool.PoolLane
import com.dynokore.platform.tenancykernel.CurrentConnection
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.postgresql.ds.PGSimpleDataSource
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
                eventually("the subscriber to apply the event") {
                    fx.count("select count(*) from submission.applied") >= 1
                }
                // once: one row per consumer, one inbox row per consumer, one event-log record
                fx.count("select count(*) from submission.applied where consumer = 'submission-svc'") shouldBe 1
                fx.count("select count(*) from submission.inbox where consumer = 'submission-svc'") shouldBe 1
                fx.count("select count(*) from eventlog.${EventLogSchema.tableName(Service.TOPIC)}") shouldBe 1
                fx.count("select count(*) from submission.outbox where published_at is not null") shouldBe 1
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
                    fx.count("select count(*) from submission.applied where consumer = 'submission-svc'") == 6L
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
                fx.count("select count(*) from submission.outbox where published_at is not null") shouldBe 0
                fx.count("select count(*) from eventlog.${EventLogSchema.tableName(Service.TOPIC)}") shouldBe 0
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
                        fx.count("select count(*) from submission.applied where consumer = 'submission-svc'") == 40L
                    }
                    val table = "eventlog.${EventLogSchema.tableName(Service.TOPIC)}"
                    fx.count("select count(*) from $table") shouldBe 40
                    fx.count("select count(distinct event_id) from $table") shouldBe 40
                    fx.count("select count(*) from submission.inbox where consumer = 'submission-svc'") shouldBe 40
                    // published in outbox seq order: log order is seq order
                    val logOrder = fx.strings("select event_id from $table order by log_offset")
                    val seqOrder = fx.strings("select id from submission.outbox order by seq")
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
                    fx.count(
                        "select count(*) from submission.event_dead_letter where consumer = 'submission-svc'",
                    ) == 1L
                }
                fx.count("select count(*) from submission.applied where consumer = 'submission-svc'") shouldBe 0
                svc.handler.attempts.get() shouldBe 3 // maxAttempts of the fixture subscription
                // the loop went on: the next event is applied
                svc.append(1)
                eventually("the next event to be applied") {
                    fx.count("select count(*) from submission.applied where consumer = 'submission-svc'") == 1L
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
            fx.count("select count(*) from submission.applied where consumer = 'submission-svc'") shouldBe 1
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
            // the relay holds a claim connection and the row's transaction, and the event log on the same pool takes a
            // third (the background lane needs 3 permits to publish; see the README)
            val lanes = arrayOf("service-runtime.pool.lanes.request=2", "service-runtime.pool.lanes.background=3")
            fx.run(mount, *lanes).use { svc ->
                val pool = svc.context.getBean("dataSource", DataSource::class.java)
                val laned = pool.unwrap(LanedDataSource::class.java)
                svc.handler.hold = CountDownLatch(1)
                svc.secondHandler.hold = svc.handler.hold
                svc.append(1)
                // both consumers sit in their handlers, each in a transaction on a background connection
                svc.handler.entered.await(20, TimeUnit.SECONDS) shouldBe true
                svc.secondHandler.entered.await(20, TimeUnit.SECONDS) shouldBe true
                // the next event: the relay takes the last permit for its claim, then waits for the row's transaction
                svc.append(1)
                eventually("the lane saturated") { laned.availablePermits(PoolLane.BACKGROUND) == 0 }
                // a request-lane thread (no lane entered) gets its connections at once, twice over
                val started = System.nanoTime()
                pool.connection.use { a ->
                    pool.connection.use { b ->
                        a.createStatement().use { it.execute("select 1") }
                        b.createStatement().use { it.execute("select 1") }
                    }
                }
                (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_500) shouldBe true
                laned.availablePermits(PoolLane.REQUEST) shouldBe 2
                laned.availablePermits(PoolLane.BACKGROUND) shouldBe 0
                svc.handler.hold!!.countDown()
                eventually("both consumers to apply both events", Duration.ofSeconds(60)) {
                    fx.count("select count(*) from submission.applied") == 4L
                }
            }
        }
    }

    /** The recording handler of one consumer; `failing` ids throw, `hold` blocks inside the transaction. */
    class RecordingHandler(
        private val consumer: String,
        private val connections: CurrentConnection,
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
            val insert = "insert into submission.applied (consumer, event_id) values (?, ?)"
            connections.require().prepareStatement(insert).use {
                it.setString(1, consumer)
                it.setString(2, event.id.value)
                it.executeUpdate()
            }
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
            connections: CurrentConnection,
            tenants: TenantContext,
        ): RecordingHandler = RecordingHandler("submission-svc", connections, tenants)

        @Bean
        fun secondHandler(
            connections: CurrentConnection,
            tenants: TenantContext,
        ): RecordingHandler = RecordingHandler("submission-svc-b", connections, tenants)

        /** A service-declared consumer per subscription; the first is primary for the replay delivery wiring. */
        @Bean
        @Primary
        @Suppress("LongParameterList")
        fun despatchConsumer(
            catalogue: EventCatalogue,
            reader: EventLogReader,
            dataSource: DataSource,
            scope: TenantScope,
            rebuildRequired: RebuildRequiredSink,
            clock: Clock,
            handler: RecordingHandler,
        ): JdbcInboxConsumer =
            JdbcInboxConsumer(
                ConsumerSubscription("submission-svc", Service.TOPIC, "submission", RETRY),
                catalogue,
                reader,
                dataSource,
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
            scope: TenantScope,
            rebuildRequired: RebuildRequiredSink,
            clock: Clock,
            @org.springframework.beans.factory.annotation.Qualifier("secondHandler") handler: RecordingHandler,
        ): JdbcInboxConsumer =
            JdbcInboxConsumer(
                ConsumerSubscription("submission-svc-b", Service.TOPIC, "submission", RETRY),
                catalogue,
                reader,
                dataSource,
                scope,
                handler,
                rebuildRequired,
                clock,
            )

        private companion object {
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
        private val fx = OutboxPostgresFixture(image)

        init {
            val catalogue = TestCatalogue.load()
            fx.createService(
                "submission",
                "create table submission.applied (consumer text not null, event_id text not null, " +
                    "primary key (consumer, event_id));" +
                    "grant select, insert on submission.applied to ${OutboxPostgresFixture.APP};" +
                    QueueSchema.ddl("submission", OutboxPostgresFixture.APP, OutboxPostgresFixture.SYS),
            )
            fx.createTopics(catalogue)
        }

        /** Starts the service; [extra] are `key=value` properties. */
        fun run(
            mount: Path,
            vararg extra: String,
            instance: String = "submission-svc-0",
        ): Service {
            val url = (fx.runtimeDataSource() as PGSimpleDataSource).getUrl().substringBefore("?")
            Files.writeString(mount.resolve(TEST_LOGIN_REF), "outbox_app_pw\n")
            val context =
                SpringApplicationBuilder(Tpl18JdbcApplication::class.java, ServiceBeans::class.java)
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
                        "service-runtime.pool.lanes.request=3",
                        "service-runtime.pool.lanes.background=4",
                        "service-runtime.pool.lanes.reprocessing=0",
                        "service-runtime.idempotency.schema=submission",
                        "spring.flyway.enabled=false",
                        "spring.main.banner-mode=off",
                        "spring.jmx.enabled=false",
                        *extra,
                    ).run()
            return Service(context, fx)
        }

        fun count(sql: String): Long =
            fx.adminDataSource().connection.use { c ->
                c.createStatement().use { s ->
                    s.executeQuery(sql).use {
                        it.next()
                        it.getLong(1)
                    }
                }
            }

        fun strings(sql: String): List<String> =
            fx.adminDataSource().connection.use { c ->
                c.createStatement().use { s ->
                    s.executeQuery(sql).use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
                }
            }

        override fun close() = fx.close()
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
