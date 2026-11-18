package com.dynokore.platform.serviceruntime.composition

import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EnvelopeJson
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.outboxandqueue.InboxConsumer
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.Lane
import com.dynokore.platform.outboxandqueue.LaneLimits
import com.dynokore.platform.outboxandqueue.OutboxSchema
import com.dynokore.platform.outboxandqueue.PollResult
import com.dynokore.platform.outboxandqueue.QueueEntry
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.ReplayDelivery
import com.dynokore.platform.outboxandqueue.ReplayOutcome
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.outboxandqueue.TestCatalogue
import com.dynokore.platform.outboxandqueue.WorkQueue
import com.dynokore.platform.serviceruntime.eventloop.EventLoopsIT
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import tools.jackson.databind.ObjectMapper
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * INV-T-24578, INV-AC-24577: replay is served for several [InboxConsumer] beans, selected by consumer name, over a
 * real PostgreSQL schema (the event-loop fixture); the consumers' handlers are fakes that record what they apply.
 * Topics: t1 = submission-despatched, t2 = submission-acknowledged, t3 = submission-failed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsumerRoutedReplayDeliveryTest {
    private lateinit var fx: EventLoopsIT.Fixture

    @TempDir
    lateinit var mount: Path

    @BeforeAll
    fun start() {
        fx = EventLoopsIT.Fixture("postgres:17")
    }

    @AfterAll
    fun stop() {
        fx.close()
    }

    @Test
    fun `INV-AC-24577 two consumers apply an item through their own bean only and the converse`() {
        run(TwoConsumers::class.java) { ctx ->
            val delivery = ctx.getBean(ReplayDelivery::class.java)
            val recorder = ctx.getBean(Recorder::class.java)
            ctx.enqueue("alpha", TYPE_T1)
            drain(delivery) shouldBe listOf(ReplayOutcome.APPLIED)
            recorder.applied("alpha-t1").size shouldBe 1
            recorder.applied("alpha-t2") shouldBe emptyList()
            recorder.applied("beta-t3") shouldBe emptyList()
            ctx.enqueue("beta", TYPE_T3)
            drain(delivery) shouldBe listOf(ReplayOutcome.APPLIED)
            recorder.applied("beta-t3").size shouldBe 1
            recorder.applied("alpha-t1").size shouldBe 1
        }
    }

    @Test
    fun `INV-AC-24577 an item for alpha and an item for beta give exactly two deliveries then null`() {
        run(TwoConsumers::class.java) { ctx ->
            val delivery = ctx.getBean(ReplayDelivery::class.java)
            ctx.enqueue("alpha", TYPE_T1)
            ctx.enqueue("beta", TYPE_T3)
            drain(delivery) shouldBe listOf(ReplayOutcome.APPLIED, ReplayOutcome.APPLIED)
            delivery.runOnce() shouldBe null
        }
    }

    @Test
    fun `INV-AC-24577 a replayed envelope of beta's topic queued for alpha is never delivered through beta`() {
        run(TwoConsumers::class.java) { ctx ->
            val delivery = ctx.getBean(ReplayDelivery::class.java)
            val recorder = ctx.getBean(Recorder::class.java)
            ctx.enqueue("alpha", TYPE_T3)
            drain(delivery) shouldBe listOf(ReplayOutcome.REJECTED)
            recorder.applied("beta-t3") shouldBe emptyList()
            // and the converse: an item queued for beta is never applied through an alpha bean; beta has one bean,
            // which serves its items as before the routing
            ctx.enqueue("beta", TYPE_T1)
            drain(delivery) shouldBe listOf(ReplayOutcome.APPLIED)
            recorder.applied("alpha-t1") shouldBe emptyList()
            recorder.applied("beta-t3").size shouldBe 1
        }
    }

    @Test
    fun `INV-AC-24577 a consumer with two beans applies a t2 item through the t2 bean`() {
        run(TwoConsumers::class.java) { ctx ->
            val delivery = ctx.getBean(ReplayDelivery::class.java)
            val recorder = ctx.getBean(Recorder::class.java)
            ctx.enqueue("alpha", TYPE_T2)
            drain(delivery) shouldBe listOf(ReplayOutcome.APPLIED)
            recorder.applied("alpha-t2").size shouldBe 1
            recorder.applied("alpha-t1") shouldBe emptyList()
        }
    }

    @Test
    fun `INV-AC-24577 an item of no topic of its consumer is dead-lettered and completed as REJECTED`() {
        run(TwoConsumers::class.java) { ctx ->
            val delivery = ctx.getBean(ReplayDelivery::class.java)
            val before = fx.count("submission.event_dead_letter")
            ctx.enqueue("alpha", TYPE_NO_TOPIC)
            drain(delivery) shouldBe listOf(ReplayOutcome.REJECTED)
            fx.count("submission.event_dead_letter") shouldBe before + 1
            delivery.runOnce() shouldBe null
            ctx.getBean(Recorder::class.java).all() shouldBe emptyList()
        }
    }

    @Test
    fun `INV-AC-24577 two beans give one dead letter for an item that is not an envelope and apply nothing`() {
        run(TwoConsumers::class.java) { ctx ->
            val delivery = ctx.getBean(ReplayDelivery::class.java)
            val before = fx.count("submission.event_dead_letter")
            ctx.enqueue("alpha", TYPE_T1, raw = "not an envelope")
            drain(delivery) shouldBe listOf(ReplayOutcome.REJECTED)
            fx.count("submission.event_dead_letter") shouldBe before + 1
            fx.strings("submission.event_dead_letter", "reason", "id").last() shouldBe "not an envelope"
            delivery.runOnce() shouldBe null
            ctx.getBean(Recorder::class.java).all() shouldBe emptyList()
        }
    }

    @Test
    fun `INV-AC-24577 a claim that fails in a two-bean consumer is released and claimed again on the next call`() {
        // consumer delta, so the item that stays queued never reaches a context of the other cases
        run(DeltaConsumer::class.java) { ctx ->
            val delivery = ctx.getBean(ReplayDelivery::class.java)
            ctx.enqueueRaw("delta", """{"no":"raw"}""")
            // the payload has no raw text: the call fails and releases the item, so it is claimed (and fails) again
            runCatching { delivery.runOnce() }.isFailure shouldBe true
            runCatching { delivery.runOnce() }.isFailure shouldBe true
            ctx.getBean(Recorder::class.java).all() shouldBe emptyList()
        }
    }

    @Test
    fun `INV-AC-24577 the stored reason of an unrouted item names the record type and the consumer`() {
        run(TwoConsumers::class.java) { ctx ->
            val delivery = ctx.getBean(ReplayDelivery::class.java)
            ctx.enqueue("alpha", TYPE_NO_TOPIC)
            drain(delivery) shouldBe listOf(ReplayOutcome.REJECTED)
            val reasons = fx.strings("submission.event_dead_letter", "reason", "id")
            val reason = reasons.last()
            reason shouldContain TYPE_NO_TOPIC
            reason shouldContain "alpha"
            reason shouldNotContain "another tenant"
        }
    }

    @Test
    fun `INV-AC-24577 a topic of a non-JDBC bean is dead-lettered with a JDBC reason and the context starts`() {
        run(MixedConsumer::class.java) { ctx ->
            val delivery = ctx.getBean(ReplayDelivery::class.java)
            ctx.enqueue("gamma", TYPE_T2)
            drain(delivery) shouldBe listOf(ReplayOutcome.REJECTED)
            val reason = fx.strings("submission.event_dead_letter", "reason", "id").last()
            reason shouldContain TYPE_T2
            reason shouldContain "JDBC"
            ctx.getBean(Recorder::class.java).all() shouldBe emptyList()
            ctx.getBean(Foreign::class.java).polls shouldBe 0
        }
    }

    @Test
    fun `INV-AC-24577 one consumer with one bean behaves as before`() {
        run(OneConsumer::class.java) { ctx ->
            val delivery = ctx.getBean(ReplayDelivery::class.java)
            val recorder = ctx.getBean(Recorder::class.java)
            ctx.enqueue("alpha", TYPE_T1)
            drain(delivery) shouldBe listOf(ReplayOutcome.APPLIED)
            recorder.applied("alpha-t1").size shouldBe 1
            ctx.enqueue("alpha", TYPE_T1, raw = "not an envelope")
            drain(delivery) shouldBe listOf(ReplayOutcome.REJECTED)
        }
    }

    @Test
    fun `INV-AC-24577 beans typed InboxConsumer give one routed ReplayDelivery bean`() {
        run(TwoConsumers::class.java) { ctx ->
            ctx
                .getBeansOfType(ReplayDelivery::class.java)
                .values
                .single()
                .shouldBeInstanceOf<ConsumerRoutedReplayDelivery>()
        }
    }

    @Test
    fun `INV-AC-24577 no InboxConsumer bean gives no ReplayDelivery bean`() {
        run(NoConsumer::class.java) { ctx ->
            ctx.getBeansOfType(ReplayDelivery::class.java) shouldBe emptyMap()
        }
    }

    @Test
    fun `INV-AC-24577 a user-declared ReplayDelivery bean is the only one`() {
        run(UserReplay::class.java) { ctx ->
            val beans = ctx.getBeansOfType(ReplayDelivery::class.java)
            beans.keys shouldBe setOf("userReplay")
        }
    }

    private fun run(
        configuration: Class<*>,
        block: (ConfigurableApplicationContext) -> Unit,
    ) {
        fx.run(mount, "service-runtime.event-loops.enabled=false", configuration = configuration).use {
            block(it.context)
        }
    }

    private fun drain(delivery: ReplayDelivery): List<ReplayOutcome> =
        generateSequence { delivery.runOnce() }.take(MAX_RUNS).toList()

    /** Queues one replay item of [type] for [consumer], as a dead-letter replay does ([raw]: an envelope). */
    private fun ConfigurableApplicationContext.enqueue(
        consumer: String,
        type: String,
        raw: String = envelopeOf(type),
    ) {
        val scope = getBean(TenantScope::class.java)
        val queue = getBean(WorkQueue::class.java)
        val payload = ObjectMapper().createObjectNode().put("raw", raw).toString()
        scope.bindWorkload(TENANT) {
            scope.transactions.inTransaction {
                queue.enqueue(QueueEntry("p-${UUID.randomUUID()}", Lane.REPLAY, payload, consumer))
            }
        }
    }

    /** Queues one replay item for [consumer] whose payload is [payload] as it stands. */
    private fun ConfigurableApplicationContext.enqueueRaw(
        consumer: String,
        payload: String,
    ) {
        val scope = getBean(TenantScope::class.java)
        val queue = getBean(WorkQueue::class.java)
        scope.bindWorkload(TENANT) {
            scope.transactions.inTransaction {
                queue.enqueue(QueueEntry("p-${UUID.randomUUID()}", Lane.REPLAY, payload, consumer))
            }
        }
    }

    private fun envelopeOf(type: String): String =
        EnvelopeJson().write(
            Envelope(
                id = EventId(UUID.randomUUID().toString()),
                source = "/gst/submission-svc",
                type = type,
                subject = "doc-1",
                time = Instant.parse("2026-11-01T00:00:00Z"),
                tenant = TENANT,
                entity = EntityRef("entity-1"),
                docUuid = null,
                transactionDate = LocalDate.of(2026, 9, 30),
                docSequence = null,
                instanceId = "submission-svc-0",
                correlation = CorrelationId("corr-1"),
                causation = CausationId("cmd-1"),
                dataJson = """{"version":1}""",
            ),
        )

    /** What each fake handler applied, by bean name. */
    class Recorder {
        private val byBean = ConcurrentHashMap<String, MutableList<String>>()

        fun handler(name: String): EventHandler =
            object : EventHandler {
                override fun handle(event: Envelope): HandleOutcome {
                    byBean.computeIfAbsent(name) { java.util.Collections.synchronizedList(mutableListOf()) } +=
                        event.id.value
                    return HandleOutcome.APPLIED
                }
            }

        fun applied(name: String): List<String> = byBean[name]?.toList().orEmpty()

        fun all(): List<String> = byBean.values.flatten()
    }

    /** The beans every case needs. */
    @Configuration(proxyBeanMethods = false)
    open class Common {
        @Bean
        fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()

        @Bean
        fun catalogue(): EventCatalogue = TestCatalogue.load()

        @Bean
        fun laneLimits(): LaneLimits = LaneLimits()

        @Bean
        fun rebuildSink(): RebuildRequiredSink = RebuildRequiredSink { }

        @Bean
        fun recorder(): Recorder = Recorder()

        /** A consumer bean typed [InboxConsumer], the declared type of the bean method. */
        fun consumer(
            name: String,
            topic: String,
            bean: String,
            recorder: Recorder,
            catalogue: EventCatalogue,
            reader: EventLogReader,
            scope: TenantScope,
            sink: RebuildRequiredSink,
            clock: Clock,
        ): InboxConsumer =
            JdbcInboxConsumer(
                ConsumerSubscription(name, topic, "submission"),
                catalogue,
                reader,
                scope,
                TenancyJooq.dslContext(OutboxSchema.jooqTables("submission"), scope.tenants, scope.transactions),
                recorder.handler(bean),
                sink,
                clock,
            )
    }

    /** alpha has two beans (t1, t2), beta one (t3). */
    @Configuration(proxyBeanMethods = false)
    @Import(Common::class)
    open class TwoConsumers(
        private val common: Common,
    ) {
        @Bean
        @Suppress("LongParameterList")
        fun alphaT1(
            recorder: Recorder,
            catalogue: EventCatalogue,
            reader: EventLogReader,
            scope: TenantScope,
            sink: RebuildRequiredSink,
            clock: Clock,
        ): InboxConsumer =
            common.consumer("alpha", TOPIC_T1, "alpha-t1", recorder, catalogue, reader, scope, sink, clock)

        @Bean
        @Suppress("LongParameterList")
        fun alphaT2(
            recorder: Recorder,
            catalogue: EventCatalogue,
            reader: EventLogReader,
            scope: TenantScope,
            sink: RebuildRequiredSink,
            clock: Clock,
        ): InboxConsumer =
            common.consumer("alpha", TOPIC_T2, "alpha-t2", recorder, catalogue, reader, scope, sink, clock)

        @Bean
        @Suppress("LongParameterList")
        fun betaT3(
            recorder: Recorder,
            catalogue: EventCatalogue,
            reader: EventLogReader,
            scope: TenantScope,
            sink: RebuildRequiredSink,
            clock: Clock,
        ): InboxConsumer = common.consumer("beta", TOPIC_T3, "beta-t3", recorder, catalogue, reader, scope, sink, clock)
    }

    /** delta has two beans (t1, t2); an item queued for it stays queued in the shared schema. */
    @Configuration(proxyBeanMethods = false)
    @Import(Common::class)
    open class DeltaConsumer(
        private val common: Common,
    ) {
        @Bean
        @Suppress("LongParameterList")
        fun deltaT1(
            recorder: Recorder,
            catalogue: EventCatalogue,
            reader: EventLogReader,
            scope: TenantScope,
            sink: RebuildRequiredSink,
            clock: Clock,
        ): InboxConsumer =
            common.consumer("delta", TOPIC_T1, "delta-t1", recorder, catalogue, reader, scope, sink, clock)

        @Bean
        @Suppress("LongParameterList")
        fun deltaT2(
            recorder: Recorder,
            catalogue: EventCatalogue,
            reader: EventLogReader,
            scope: TenantScope,
            sink: RebuildRequiredSink,
            clock: Clock,
        ): InboxConsumer =
            common.consumer("delta", TOPIC_T2, "delta-t2", recorder, catalogue, reader, scope, sink, clock)
    }

    /** A bean of another [InboxConsumer] type; replay never touches it. */
    class Foreign : InboxConsumer {
        var polls = 0

        override fun pollOnce(): PollResult {
            polls++
            return PollResult(0, 0, 0, 0, 0)
        }
    }

    /** gamma: a JDBC bean for t1 and a non-JDBC bean (assumed for t2). */
    @Configuration(proxyBeanMethods = false)
    @Import(Common::class)
    open class MixedConsumer(
        private val common: Common,
    ) {
        @Bean
        @Suppress("LongParameterList")
        fun gammaT1(
            recorder: Recorder,
            catalogue: EventCatalogue,
            reader: EventLogReader,
            scope: TenantScope,
            sink: RebuildRequiredSink,
            clock: Clock,
        ): InboxConsumer =
            common.consumer("gamma", TOPIC_T1, "gamma-t1", recorder, catalogue, reader, scope, sink, clock)

        @Bean
        fun foreign(): Foreign = Foreign()
    }

    /** One consumer, one bean. */
    @Configuration(proxyBeanMethods = false)
    @Import(Common::class)
    open class OneConsumer(
        private val common: Common,
    ) {
        @Bean
        @Suppress("LongParameterList")
        fun alphaT1(
            recorder: Recorder,
            catalogue: EventCatalogue,
            reader: EventLogReader,
            scope: TenantScope,
            sink: RebuildRequiredSink,
            clock: Clock,
        ): InboxConsumer =
            common.consumer("alpha", TOPIC_T1, "alpha-t1", recorder, catalogue, reader, scope, sink, clock)
    }

    /** No consumer bean. */
    @Configuration(proxyBeanMethods = false)
    @Import(Common::class)
    open class NoConsumer

    /** A consumer bean and the service's own [ReplayDelivery]. */
    @Configuration(proxyBeanMethods = false)
    @Import(OneConsumer::class)
    open class UserReplay {
        @Bean
        fun userReplay(): ReplayDelivery =
            object : ReplayDelivery {
                override fun runOnce(): ReplayOutcome? = null
            }
    }

    private companion object {
        val TENANT = TenantId(UUID.fromString("6f1c1a52-0000-4000-8000-000000000001"))
        const val MAX_RUNS = 20
        const val TOPIC_T1 = "gst.submission.submission-despatched.v1"
        const val TOPIC_T2 = "gst.submission.submission-acknowledged.v1"
        const val TOPIC_T3 = "gst.submission.submission-failed.v1"
        const val TYPE_T1 = TestCatalogue.SUBMISSION_DESPATCHED
        const val TYPE_T2 = TestCatalogue.SUBMISSION_ACKNOWLEDGED
        const val TYPE_T3 = "com.dynokore.gst.submission.SubmissionFailed.v1"
        const val TYPE_NO_TOPIC = TestCatalogue.PAYLOAD_READY
    }
}
