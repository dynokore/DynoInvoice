package com.dynokore.platform.productruntime

import com.dynokore.platform.auditlog.OutboxAuditWriter
import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.EventLogHead
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.InboxConsumer
import com.dynokore.platform.outboxandqueue.JdbcOutboxWriter
import com.dynokore.platform.outboxandqueue.OutboxPostgresFixture
import com.dynokore.platform.outboxandqueue.OutboxSchema
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.outboxandqueue.PostgresEventBinding
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.ReplayProtocol
import com.dynokore.platform.outboxandqueue.ReplayRequestTopic
import com.dynokore.platform.outboxandqueue.ReplayTopic
import com.dynokore.platform.outboxandqueue.SigningFixture
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.productruntime.feed.AuthorityHeadFeed
import com.dynokore.platform.serviceruntime.binding.GstProperties
import com.dynokore.platform.serviceruntime.composition.ReplicaColdStartWiring
import com.dynokore.platform.serviceruntime.rebuild.ReplicaColdStartSync
import com.dynokore.platform.serviceruntime.replica.AuthorityFallback
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorityVerdict
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.DecisionSource
import com.dynokore.platform.serviceruntime.replica.FallbackResult
import com.dynokore.platform.serviceruntime.replica.Lag
import com.dynokore.platform.serviceruntime.replica.PolicyReplica
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * INV-T-9059, INV-AC-9051 (the start-up test the audit asked for): the product-runtime configuration, service-runtime's
 * production cold-start beans (INV-T-13608) and the authority wiring boot against PostgreSQL; the stub producer answers
 * the service's own full-sync request on the replay topic and a stub access-svc counts the authorise calls. No runtime,
 * intake, listener or sync bean is defined here: [assertProductionBeans] fails when one is.
 */
class AuthorityStartUpIT {
    private class MutableClock(
        private var now: Instant,
    ) : Clock() {
        override fun instant(): Instant = now

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        fun advance(by: Duration) {
            now = now.plus(by)
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(GstProperties::class)
    class Properties

    /** The stub access-svc: it counts the authorise calls and allows. */
    private class StubAccess : AuthorityFallback {
        val calls = AtomicInteger()

        override fun fetch(
            key: AuthorizationRequest,
            deadline: Deadline,
        ): FallbackResult<AuthorityVerdict> {
            calls.incrementAndGet()
            return FallbackResult.Found(AuthorityVerdict(true))
        }
    }

    private inner class Harness(
        val clock: MutableClock = MutableClock(Instant.parse("2026-10-30T08:00:00Z")),
    ) : AutoCloseable {
        val fixture =
            OutboxPostgresFixture(
                IMAGE,
                OutboxPostgresFixture.serviceInitSql(SCHEMA) + OutboxPostgresFixture.eventLogInitSql(CATALOGUE),
            )
        val access = StubAccess()
        val replica = AuthorityReplica(clock, fallback = access)
        private val tenants = TenantContextImpl()
        private val runner = TransactionRunnerImpl(fixture.runtimePool(), tenants, null, SigningFixture.keys())
        private val scope = TenantScope(tenants, runner, runner, OutboxPostgresFixture.WORKLOAD)
        private val outbox =
            JdbcOutboxWriter(
                TenancyJooq.dslContext(OutboxSchema.jooqTables(SCHEMA), tenants, runner),
                SCHEMA,
                CONSUMER,
                "$CONSUMER-1",
                CATALOGUE,
                clock,
            )
        private val eventLog = fixture.eventLogScope(TenantContextImpl())
        private val producerBinding = PostgresEventBinding(eventLog, fixture.eventLogDsl(eventLog))
        private val mapper = ObjectMapper()

        /** The service's context: production configuration classes only, over the fixture's inputs. */
        fun runner(removeIntake: Boolean = false): ApplicationContextRunner =
            ApplicationContextRunner()
                .withUserConfiguration(Properties::class.java, ReplicaColdStartWiring::class.java)
                .withBean(Clock::class.java, { clock })
                .withBean(EventCatalogue::class.java, { CATALOGUE })
                .withBean(TenantScope::class.java, { scope })
                .withBean(OutboxWriter::class.java, { outbox })
                .withBean(com.dynokore.platform.auditlog.AuditWriter::class.java, { OutboxAuditWriter(outbox) })
                .withBean(
                    "liveEventLogHead",
                    EventLogHead::class.java,
                    {
                        // the head is read on the live reader; the replay reader below is an EventLogHead too
                        val live = fixture.eventLogReader()
                        EventLogHead { topic, from -> live.head(topic, from) }
                    },
                    { it.isPrimary = true },
                ).withBean(EventLogReader::class.java, { fixture.eventLogReaderFor(fixture.replayLogin(CONSUMER)) })
                .withBean(RebuildRequiredSink::class.java, { mockk<RebuildRequiredSink>(relaxed = true) })
                .withBean(
                    "authoritySubscription",
                    ConsumerSubscription::class.java,
                    { ConsumerSubscription(CONSUMER, AUTHORITY, SCHEMA) },
                ).withBean(AuthorityReplica::class.java, { replica })
                .withBean(StructureReplica::class.java, { StructureReplica(clock) })
                .withBean(PolicyReplica::class.java, { PolicyReplica(clock) })
                .withBean("internalRestClientBuilder", RestClient.Builder::class.java, { RestClient.builder() })
                .withBean(
                    "withoutIntake",
                    BeanDefinitionRegistryPostProcessor::class.java,
                    {
                        object : BeanDefinitionRegistryPostProcessor {
                            override fun postProcessBeanDefinitionRegistry(registry: BeanDefinitionRegistry) {
                                if (removeIntake) registry.removeBeanDefinition("authorityReplayIntake")
                            }

                            override fun postProcessBeanFactory(
                                beanFactory: org.springframework.beans.factory.config.ConfigurableListableBeanFactory,
                            ) = Unit
                        }
                    },
                ).withConfiguration(
                    AutoConfigurations.of(ProductRuntimeConfiguration::class.java, AuthorityReplicaWiring::class.java),
                ).withPropertyValues("gst.service=$CONSUMER")

        fun ready(context: ConfigurableApplicationContext) =
            context.publishEvent(ApplicationReadyEvent(SpringApplication(), emptyArray(), context, Duration.ZERO))

        /** The replay id of the full sync the service wrote to its outbox (what the producer's responder reads). */
        fun requestedReplay(): UUID =
            fixture.adminDataSource().connection.use { c ->
                c.prepareStatement("select data::text from $SCHEMA.outbox where type = ?").use { st ->
                    st.setString(1, ReplayProtocol.REPLAY_REQUESTED)
                    st.executeQuery().use { rs ->
                        check(rs.next()) { "the service wrote no ReplayRequested" }
                        UUID.fromString(mapper.readTree(rs.getString(1)).get("replayId").asString())
                    }
                }
            }

        /** The stub producer's answer: [versions] AuthorityChanged events, then the marker of [replayId]. */
        fun answer(
            replayId: UUID,
            versions: Int = 3,
        ) {
            (1..versions).forEach { v ->
                producerBinding.publish(REPLAY_TOPIC, authorityChanged(v))
            }
            producerBinding.publish(REPLAY_TOPIC, replayCompleted(replayId, versions))
        }

        fun pump(context: ConfigurableApplicationContext) {
            if (context.containsBean("authorityReplayIntake")) {
                val intake = context.getBean("authorityReplayIntake", InboxConsumer::class.java)
                repeat(PUMPS) { intake.pollOnce() }
            }
        }

        fun heartbeat(
            context: ConfigurableApplicationContext,
            version: Long,
        ) = context
            .getBean(AuthorityHeadFeed::class.java)
            .handle(envelope(ProductEventTypes.AUTHORITY_HEAD_OBSERVED, """{"authorityVersion":$version}"""))

        fun read(): DecisionSource =
            replica
                .decide(
                    AuthorizationRequest(TENANT, Principal("P-1", PrincipalKind.HUMAN), "E-001", "document.read"),
                    Deadline.after(Duration.ofSeconds(1), clock),
                ).source

        private fun authorityChanged(version: Int) =
            envelope(
                PlatformEventCatalogue.AUTHORITY_CHANGED.eventType,
                """{"changeKind":"GRANT","principalRef":"P-1","authorityVersion":$version,
                |"grantRecord":{"entityRef":"E-001","grants":[{"action":"document.read","scopeLevel":"ENTITY",
                |"scopeRef":"E-001","validFrom":"2026-10-01T00:00:00Z"}]}}
                """.trimMargin(),
            )

        private fun replayCompleted(
            replayId: UUID,
            replayed: Int,
        ) = envelope(
            ReplayProtocol.REPLAY_COMPLETED,
            """{"replayId":"$replayId","eventsReplayed":$replayed}""",
            tenant = TenantId.PLATFORM,
            entity = EntityRef.PLATFORM,
            subject = "replay/$replayId",
        )

        private fun envelope(
            type: String,
            data: String,
            tenant: TenantId = TENANT,
            entity: EntityRef = EntityRef("E-001"),
            subject: String = "P-1",
        ) = Envelope(
            id = EventId(UUID.randomUUID().toString()),
            source = "/gst/$ACCESS",
            type = type,
            subject = subject,
            time = clock.instant(),
            tenant = tenant,
            entity = entity,
            docUuid = null,
            transactionDate = null,
            docSequence = null,
            instanceId = "$ACCESS-1",
            correlation = CorrelationId("corr-${UUID.randomUUID()}"),
            causation = CausationId("cause-${UUID.randomUUID()}"),
            dataJson = data,
        )

        override fun close() = fixture.close()
    }

    /** The four beans of the task are defined by production configuration classes, never by test code. */
    private fun assertProductionBeans(context: ConfigurableApplicationContext) {
        val production =
            mapOf(
                "replicaColdStartSync" to ReplicaColdStartWiring::class.java.name,
                "authorityReplayIntake" to AuthorityReplicaWiring::class.java.name,
                "authorityReplayCompletionListener" to AuthorityReplicaWiring::class.java.name,
                "authorityHeadFeed" to AuthorityReplicaWiring::class.java.name,
            )
        production.forEach { (name, factory) ->
            val definition: BeanDefinition = context.beanFactory.getBeanDefinition(name)
            context.getType(checkNotNull(definition.factoryBeanName))?.name shouldBe factory
        }
    }

    @Test
    fun `before a snapshot the fallback decides, after the own full sync the next 100 reads are local`() {
        Harness().use { h ->
            h.runner().run { context ->
                (context.startupFailure == null) shouldBe true
                assertProductionBeans(context)
                h.ready(context)
                h.read() shouldBe DecisionSource.FALLBACK
                h.access.calls.get() shouldBe 1

                h.answer(h.requestedReplay())
                h.pump(context)

                repeat(100) { h.read() shouldBe DecisionSource.LOCAL }
                h.access.calls.get() shouldBe 1
            }
        }
    }

    @Test
    fun `1001 ms without a heartbeat sends the read to the fallback, exactly 1000 ms does not`() {
        listOf(1000L to DecisionSource.LOCAL, 1001L to DecisionSource.FALLBACK).forEach { (silence, expected) ->
            Harness().use { h ->
                h.runner().run { context ->
                    h.ready(context)
                    h.answer(h.requestedReplay())
                    h.pump(context)
                    h.heartbeat(context, 3)
                    h.clock.advance(Duration.ofMillis(silence))
                    h.read() shouldBe expected
                    h.access.calls.get() shouldBe if (expected == DecisionSource.LOCAL) 0 else 1
                }
            }
        }
    }

    @Test
    fun `with a heartbeat every 500 ms for 5 s of fixed-clock time all reads stay local`() {
        Harness().use { h ->
            h.runner().run { context ->
                h.ready(context)
                h.answer(h.requestedReplay())
                h.pump(context)
                repeat(10) {
                    h.clock.advance(Duration.ofMillis(500))
                    h.heartbeat(context, 3)
                    h.read() shouldBe DecisionSource.LOCAL
                }
                h.access.calls.get() shouldBe 0
            }
        }
    }

    @Test
    fun `the marker of a replay the service did not create leaves the first read on the fallback`() {
        Harness().use { h ->
            h.runner().run { context ->
                h.ready(context)
                h.answer(UUID.randomUUID())
                h.pump(context)
                h.replica.tracker.lag() shouldBe Lag.Unknown
                h.read() shouldBe DecisionSource.FALLBACK
                h.access.calls.get() shouldBe 1
            }
        }
    }

    @Test
    fun `without the intake bean the listener receives no completion and the first read stays on the fallback`() {
        Harness().use { h ->
            h.runner(removeIntake = true).run { context ->
                (context.containsBean("authorityReplayIntake")) shouldBe false
                h.ready(context)
                h.answer(h.requestedReplay())
                h.pump(context)
                h.read() shouldBe DecisionSource.FALLBACK
                h.access.calls.get() shouldBe 1
            }
        }
    }

    private companion object {
        const val IMAGE = "postgres:17"
        const val SCHEMA = "reconciliation"
        const val CONSUMER = "reconciliation-svc"
        const val ACCESS = "access-svc"
        const val PUMPS = 12
        val AUTHORITY: String = ReplicaColdStartSync.TOPIC
        const val REPLAY_TOPIC = "gst.access.replay-reconciliation.v1"
        val TENANT = TenantId(UUID.fromString("0a000000-0000-0000-0000-00000000000a"))

        val CATALOGUE =
            EventCatalogue(
                listOf(
                    CatalogueEntry(
                        PlatformEventCatalogue.AUTHORITY_CHANGED.eventType,
                        AUTHORITY,
                        setOf(ACCESS),
                        false,
                        recordTopic = true,
                        retainedWhileResolvable = false,
                    ),
                    CatalogueEntry(
                        OutboxAuditWriter.EVENT_TYPE,
                        "gst.platform.audit-entry-recorded.v1",
                        setOf(CatalogueEntry.EVERY_SERVICE),
                        false,
                        retainedWhileResolvable = false,
                    ),
                ),
                listOf(
                    ReplayTopic(
                        REPLAY_TOPIC,
                        setOf(PlatformEventCatalogue.AUTHORITY_CHANGED.eventType, ReplayProtocol.REPLAY_COMPLETED),
                        ACCESS,
                        CONSUMER,
                        false,
                        retainedWhileResolvable = false,
                    ),
                ),
                listOf(ReplayRequestTopic("gst.reconciliation.replay-requested.v1", CONSUMER, setOf(ACCESS))),
            )
    }
}
