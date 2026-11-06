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
import com.dynokore.platform.outboxandqueue.ReplayCompletionListener
import com.dynokore.platform.outboxandqueue.ReplayProtocol
import com.dynokore.platform.outboxandqueue.ReplayRequestTopic
import com.dynokore.platform.outboxandqueue.ReplayTopic
import com.dynokore.platform.outboxandqueue.SigningFixture
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.binding.GstProperties
import com.dynokore.platform.serviceruntime.composition.ReplicaColdStartWiring
import com.dynokore.platform.serviceruntime.rebuild.ReplicaColdStartSync
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.Lookup
import com.dynokore.platform.serviceruntime.replica.PolicyReplica
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

/**
 * INV-T-9095, INV-AC-9089 (start-up test, model AuthorityStartUpIT): the product-runtime configuration, service-runtime's
 * production cold-start beans and the structure wiring boot against PostgreSQL; the stub tenancy-svc answers the
 * service's own full-sync request on the replay topic. [StructureReplica.isSynced] turns true only when the marker of
 * the service's own replay arrives after both listed topics moved.
 */
class StructureStartUpIT {
    private class MutableClock(
        private var now: Instant,
    ) : Clock() {
        override fun instant(): Instant = now

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(GstProperties::class)
    class Properties

    private inner class Harness(
        val clock: MutableClock = MutableClock(Instant.parse("2026-10-30T08:00:00Z")),
    ) : AutoCloseable {
        val fixture =
            OutboxPostgresFixture(
                IMAGE,
                OutboxPostgresFixture.serviceInitSql(SCHEMA) + OutboxPostgresFixture.eventLogInitSql(CATALOGUE),
            )
        val structure = StructureReplica(clock)
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

        fun runner(): ApplicationContextRunner =
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
                        val live = fixture.eventLogReader()
                        EventLogHead { topic, from -> live.head(topic, from) }
                    },
                    { it.isPrimary = true },
                ).withBean(EventLogReader::class.java, { fixture.eventLogReaderFor(fixture.replayLogin(CONSUMER)) })
                .withBean(RebuildRequiredSink::class.java, { mockk<RebuildRequiredSink>(relaxed = true) })
                .withBean(
                    "structureSubscription",
                    ConsumerSubscription::class.java,
                    { ConsumerSubscription(CONSUMER, STRUCTURE_TOPIC, SCHEMA) },
                ).withBean(
                    "profileSubscription",
                    ConsumerSubscription::class.java,
                    { ConsumerSubscription(CONSUMER, PROFILE_TOPIC, SCHEMA) },
                ).withBean(AuthorityReplica::class.java, { AuthorityReplica(clock) })
                .withBean(StructureReplica::class.java, { structure })
                .withBean(PolicyReplica::class.java, { PolicyReplica(clock) })
                .withBean("internalRestClientBuilder", RestClient.Builder::class.java, { RestClient.builder() })
                .withConfiguration(
                    AutoConfigurations.of(ProductRuntimeConfiguration::class.java, StructureReplicaWiring::class.java),
                ).withPropertyValues(
                    "gst.service=$CONSUMER",
                    "${ReplicaColdStartSync.REQUIRE_SNAPSHOT_SETTING}=true",
                    "serviceruntime.replica.structure.snapshot-topics=$STRUCTURE_TOPIC,$PROFILE_TOPIC",
                )

        fun ready(context: ConfigurableApplicationContext) =
            context.publishEvent(ApplicationReadyEvent(SpringApplication(), emptyArray(), context, Duration.ZERO))

        /** The replay id of the full sync the service wrote to its outbox. */
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

        /** The producer moved only [STRUCTURE_TOPIC]: the replay row of the request keeps that one topic only. */
        fun narrowToFirstTopic() {
            fixture.adminDataSource().connection.use { c ->
                c.prepareStatement("update $SCHEMA.${OutboxSchema.CONSUMER_REPLAY} set topics = array[?]").use { st ->
                    st.setString(1, STRUCTURE_TOPIC)
                    check(st.executeUpdate() == 1) { "expected one replay row" }
                }
            }
        }

        /** The stub producer's answer: [nodes] structure events, [profiles] profile events, then the marker if any. */
        fun answer(
            marker: UUID?,
            nodes: Int = 3,
            profiles: Int = 2,
        ) {
            var version = 0
            (1..nodes).forEach { producerBinding.publish(REPLAY_TOPIC, structureChanged("N-$it", ++version)) }
            (1..profiles).forEach { producerBinding.publish(REPLAY_TOPIC, profileChanged("E-$it", ++version)) }
            if (marker != null) producerBinding.publish(REPLAY_TOPIC, replayCompleted(marker, nodes + profiles))
        }

        fun pump(context: ConfigurableApplicationContext) {
            val intake = context.getBean("structureReplayIntake", InboxConsumer::class.java)
            repeat(PUMPS) { intake.pollOnce() }
        }

        fun present(ref: String): Boolean =
            structure.node(TENANT, ref, Deadline.after(Duration.ofSeconds(1), clock)) is Lookup.Local

        private fun node(version: Int) =
            """{"kind":"ENTITY","parentRef":"G-1","retired":false,"name":"n$version","code":"C$version",
            |"profile":[{"attribute":"filingFrequency","value":"QUARTERLY","effectiveDate":"2026-01-01"}],
            |"structureVersion":$version}
            """.trimMargin()

        private fun structureChanged(
            ref: String,
            version: Int,
        ) = envelope(
            ProductEventTypes.ORGANISATION_STRUCTURE_CHANGED,
            """{"nodeType":"ENTITY","nodeRef":"$ref","changeType":"UPDATED","parentRef":"G-1","node":${node(
                version,
            )}}""",
            EntityRef(ref),
        )

        private fun profileChanged(
            ref: String,
            version: Int,
        ) = envelope(
            ProductEventTypes.ENTITY_PROFILE_CHANGED,
            """{"entityRef":"$ref","changedAttributes":[{"attribute":"filingFrequency","newValue":"MONTHLY"}],
            |"effectiveDate":"2026-07-01","node":${node(version)}}
            """.trimMargin(),
            EntityRef(ref),
        )

        private fun replayCompleted(
            replayId: UUID,
            replayed: Int,
        ) = envelope(
            ReplayProtocol.REPLAY_COMPLETED,
            """{"replayId":"$replayId","eventsReplayed":$replayed}""",
            EntityRef.PLATFORM,
            tenant = TenantId.PLATFORM,
            subject = "replay/$replayId",
        )

        private fun envelope(
            type: String,
            data: String,
            entity: EntityRef,
            tenant: TenantId = TENANT,
            subject: String = "tenancy",
        ) = Envelope(
            id = EventId(UUID.randomUUID().toString()),
            source = "/gst/$TENANCY",
            type = type,
            subject = subject,
            time = clock.instant(),
            tenant = tenant,
            entity = entity,
            docUuid = null,
            transactionDate = null,
            docSequence = null,
            instanceId = "$TENANCY-1",
            correlation = CorrelationId("corr-${UUID.randomUUID()}"),
            causation = CausationId("cause-${UUID.randomUUID()}"),
            dataJson = data,
        )

        override fun close() = fixture.close()
    }

    private fun check(
        replayMarker: (Harness) -> UUID?,
        nodes: Int = 3,
        profiles: Int = 2,
        narrow: Boolean = false,
        synced: Boolean,
        present: Int,
    ) {
        Harness().use { h ->
            h.runner().run { context ->
                (context.startupFailure == null) shouldBe true
                listOf("structureReplayIntake", "structureReplayCompletionListener").forEach {
                    val definition: BeanDefinition = context.beanFactory.getBeanDefinition(it)
                    context.getType(checkNotNull(definition.factoryBeanName))?.name shouldBe
                        StructureReplicaWiring::class.java.name
                }
                h.ready(context)
                h.structure.isSynced() shouldBe false
                if (narrow) h.narrowToFirstTopic()
                h.answer(replayMarker(h), nodes, profiles)
                h.pump(context)
                h.structure.isSynced() shouldBe synced
                listOf("N-1", "N-2", "N-3", "E-1", "E-2").count { h.present(it) } shouldBe present
            }
        }
    }

    @Test
    fun `an own replay that moved both topics and ended in its marker syncs the replica with 5 states`() {
        check({ it.requestedReplay() }, synced = true, present = 5)
    }

    @Test
    fun `an own replay that moved only the first topic leaves the replica unsynced`() {
        check({ it.requestedReplay() }, profiles = 0, narrow = true, synced = false, present = 3)
    }

    @Test
    fun `the same events without the marker leave the replica unsynced`() {
        check({ null }, synced = false, present = 5)
    }

    @Test
    fun `the marker of a foreign replay id leaves the replica unsynced`() {
        check({ UUID.randomUUID() }, synced = false, present = 5)
    }

    @Test
    fun `a completion of the authority replay with the own id value on another topic leaves the replica unsynced`() {
        Harness().use { h ->
            h.runner().run { context ->
                h.ready(context)
                val replayId = h.requestedReplay()
                h.answer(null)
                h.pump(context)
                context
                    .getBean("structureReplayCompletionListener", ReplayCompletionListener::class.java)
                    .onReplayCompleted(replayId, CONSUMER, listOf(AUTHORITY_TOPIC))
                h.structure.isSynced() shouldBe false
            }
        }
    }

    private companion object {
        const val IMAGE = "postgres:17"
        const val SCHEMA = "reconciliation"
        const val CONSUMER = "reconciliation-svc"
        const val TENANCY = "tenancy-svc"
        const val PUMPS = 12
        const val STRUCTURE_TOPIC = ProductEventTypes.ORGANISATION_STRUCTURE_CHANGED_TOPIC
        const val PROFILE_TOPIC = ProductEventTypes.ENTITY_PROFILE_CHANGED_TOPIC
        val AUTHORITY_TOPIC: String = ReplicaColdStartSync.TOPIC
        const val REPLAY_TOPIC = "gst.tenancy.replay-reconciliation.v1"
        val TENANT = TenantId(UUID.fromString("0a000000-0000-0000-0000-00000000000a"))

        val CATALOGUE =
            EventCatalogue(
                listOf(
                    CatalogueEntry(
                        ProductEventTypes.ORGANISATION_STRUCTURE_CHANGED,
                        STRUCTURE_TOPIC,
                        setOf(TENANCY),
                        false,
                        recordTopic = true,
                        retainedWhileResolvable = false,
                    ),
                    CatalogueEntry(
                        ProductEventTypes.ENTITY_PROFILE_CHANGED,
                        PROFILE_TOPIC,
                        setOf(TENANCY),
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
                        setOf(
                            ProductEventTypes.ORGANISATION_STRUCTURE_CHANGED,
                            ProductEventTypes.ENTITY_PROFILE_CHANGED,
                            ReplayProtocol.REPLAY_COMPLETED,
                        ),
                        TENANCY,
                        CONSUMER,
                        false,
                        retainedWhileResolvable = false,
                    ),
                ),
                listOf(ReplayRequestTopic("gst.reconciliation.replay-requested.v1", CONSUMER, setOf(TENANCY))),
            )
    }
}
