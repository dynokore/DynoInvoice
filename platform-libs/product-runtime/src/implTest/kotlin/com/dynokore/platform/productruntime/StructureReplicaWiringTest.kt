package com.dynokore.platform.productruntime

import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcReplayIntake
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.ReplayCompletionListener
import com.dynokore.platform.outboxandqueue.ReplayProtocol
import com.dynokore.platform.outboxandqueue.ReplayRequestTopic
import com.dynokore.platform.outboxandqueue.ReplayTopic
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.productruntime.Fixtures.CLOCK
import com.dynokore.platform.productruntime.feed.StructureFeedHandler
import com.dynokore.platform.serviceruntime.binding.GstProperties
import com.dynokore.platform.serviceruntime.rebuild.ReplicaColdStartSync
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.CurrentConnection
import com.dynokore.platform.tenancykernel.SqlErrors
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.MapPropertySource
import java.time.Clock
import java.util.UUID

/**
 * INV-T-9095 (P109, TPL-18): the structure intake and listener exist only for the setting exactly `true` and the three
 * beans; the listener completes the snapshot only for an own replay that moved every listed topic.
 */
class StructureReplicaWiringTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(GstProperties::class)
    class Properties

    private val first = ProductEventTypes.ORGANISATION_STRUCTURE_CHANGED_TOPIC
    private val second = ProductEventTypes.ENTITY_PROFILE_CHANGED_TOPIC
    private val own = UUID.randomUUID()
    private val sync =
        mockk<ReplicaColdStartSync>(relaxed = true) {
            every { isOwnReplay(any()) } answers { firstArg<UUID>() == own }
        }
    private val runnerStub =
        mockk<TransactionRunner>(moreInterfaces = arrayOf(SqlErrors::class, CurrentConnection::class))
    private val scope = TenantScope(mockk<TenantContext>(), runnerStub, runnerStub as CurrentConnection, "svc-workload")
    private val require = ReplicaColdStartSync.REQUIRE_SNAPSHOT_SETTING
    private val topicsSetting = "serviceruntime.replica.structure.snapshot-topics"

    private fun catalogue(withAuthority: Boolean = false) =
        EventCatalogue(
            listOfNotNull(
                CatalogueEntry(ProductEventTypes.ORGANISATION_STRUCTURE_CHANGED, first, setOf("tenancy-svc")),
                CatalogueEntry(ProductEventTypes.ENTITY_PROFILE_CHANGED, second, setOf("tenancy-svc")),
                if (withAuthority) {
                    CatalogueEntry(
                        PlatformEventCatalogue.AUTHORITY_CHANGED.eventType,
                        PlatformEventCatalogue.AUTHORITY_CHANGED.topic!!,
                        setOf("access-svc"),
                    )
                } else {
                    null
                },
            ),
            listOfNotNull(
                if (withAuthority) {
                    ReplayTopic(
                        "gst.access.replay-reconciliation.v1",
                        setOf(PlatformEventCatalogue.AUTHORITY_CHANGED.eventType, ReplayProtocol.REPLAY_COMPLETED),
                        "access-svc",
                        "reconciliation-svc",
                        false,
                    )
                } else {
                    null
                },
                ReplayTopic(
                    "gst.tenancy.replay-reconciliation.v1",
                    setOf(
                        ProductEventTypes.ORGANISATION_STRUCTURE_CHANGED,
                        ProductEventTypes.ENTITY_PROFILE_CHANGED,
                        ReplayProtocol.REPLAY_COMPLETED,
                    ),
                    "tenancy-svc",
                    "reconciliation-svc",
                    false,
                ),
            ),
            listOf(
                ReplayRequestTopic(
                    "gst.reconciliation.replay-requested.v1",
                    "reconciliation-svc",
                    setOf("tenancy-svc"),
                ),
            ),
        )

    private fun runner(
        replica: StructureReplica? = StructureReplica(CLOCK),
        withSync: Boolean = true,
        withHandler: Boolean = true,
        catalogue: EventCatalogue = catalogue(),
        consumer2: String = "reconciliation-svc",
        schema2: String = "reconciliation",
    ): ApplicationContextRunner {
        var r =
            ApplicationContextRunner()
                .withUserConfiguration(Properties::class.java)
                .withBean(Clock::class.java, { CLOCK })
                .withBean(EventCatalogue::class.java, { catalogue })
                .withBean(TenantScope::class.java, { scope })
                .withBean(EventLogReader::class.java, { mockk<EventLogReader>() })
                .withBean(RebuildRequiredSink::class.java, { mockk<RebuildRequiredSink>() })
                .withBean("s1", ConsumerSubscription::class.java, {
                    ConsumerSubscription("reconciliation-svc", first, "reconciliation")
                })
                .withBean(
                    "s2",
                    ConsumerSubscription::class.java,
                    { ConsumerSubscription(consumer2, second, schema2) },
                ).withConfiguration(AutoConfigurations.of(StructureReplicaWiring::class.java))
                .withPropertyValues("gst.service=reconciliation-svc")
        if (replica != null) r = r.withBean(StructureReplica::class.java, { replica })
        if (withSync) r = r.withBean(ReplicaColdStartSync::class.java, { sync })
        if (withHandler) {
            r =
                r.withBean(
                    StructureFeedHandler::class.java,
                    { StructureFeedHandler(replica ?: StructureReplica(CLOCK)) },
                )
        }
        return r
    }

    private fun on(r: ApplicationContextRunner) = r.withPropertyValues("$require=true", "$topicsSetting=$first,$second")

    private val names = listOf("structureReplayCompletionListener", "structureReplayIntake")

    @Test
    fun `the setting true with the three beans installs the listener and the intake`() {
        on(runner()).run { context ->
            context.startupFailure shouldBe null
            context.getBeansOfType(JdbcReplayIntake::class.java).keys shouldBe setOf("structureReplayIntake")
            context.getBeansOfType(ReplayCompletionListener::class.java).keys shouldBe
                setOf("structureReplayCompletionListener")
            names.forEach {
                context.beanFactory.getBeanDefinition(it).factoryBeanName shouldBe
                    StructureReplicaWiring::class.java.name
            }
        }
    }

    @Test
    fun `an absent, false or look-alike setting installs neither bean`() {
        listOf(null, "false", "True", "TRUE", "yes", "1", "", "on").forEach { value ->
            val base = runner().withPropertyValues("$topicsSetting=$first,$second")
            val configured = if (value == null) base else base.withPropertyValues("$require=$value")
            configured.run { context ->
                context.startupFailure shouldBe null
                names.forEach { context.containsBean(it) shouldBe false }
            }
        }
    }

    @Test
    fun `without the replica, the sync or the handler neither bean exists`() {
        listOf(
            on(runner(replica = null, withHandler = false)),
            on(runner(withSync = false)),
            on(runner(withHandler = false)),
        ).forEach { configured ->
            configured.run { context ->
                context.startupFailure shouldBe null
                names.forEach { context.containsBean(it) shouldBe false }
            }
        }
    }

    @Test
    fun `subscriptions of different consumers or schemas stop the start and name the setting`() {
        listOf(runner(consumer2 = "other-svc"), runner(schema2 = "other")).forEach { r ->
            on(r).run { context ->
                context.startupFailure.toString() shouldContain topicsSetting
            }
        }
    }

    @Test
    fun `a listed topic without a subscription stops the start and names the setting`() {
        runner()
            .withPropertyValues("$require=true", "$topicsSetting=$first,gst.tenancy.other.v1")
            .run { context -> context.startupFailure.toString() shouldContain topicsSetting }
    }

    @Test
    fun `the listener completes the snapshot only for an own replay that moved every listed topic`() {
        val authority = PlatformEventCatalogue.AUTHORITY_CHANGED.topic!!
        val cases =
            listOf(
                Triple(own, listOf(first, second), true),
                Triple(own, listOf(second, first, authority), true),
                Triple(own, listOf(first), false),
                Triple(own, listOf(second), false),
                Triple(own, listOf(authority), false),
                Triple(own, emptyList(), false),
                Triple(UUID.randomUUID(), listOf(first, second), false),
            )
        cases.forEach { (replayId, topics, snapshot) ->
            val fresh = StructureReplica(CLOCK)
            on(runner(replica = fresh)).run { context ->
                fresh.replica.tracker.onApplied(7)
                context
                    .getBean(ReplayCompletionListener::class.java)
                    .onReplayCompleted(replayId, "reconciliation-svc", topics)
                fresh.isSynced() shouldBe snapshot
                fresh.replica.tracker.appliedVersion() shouldBe 7
            }
        }
    }

    @Test
    fun `an emptied topic list fails closed and a full one calls the replica exactly once`() {
        val spied = mockk<StructureReplica>(relaxed = true)
        every { spied.replica } returns StructureReplica(CLOCK).replica
        on(runner(replica = spied)).run { context ->
            val listener = context.getBean(ReplayCompletionListener::class.java)
            listener.onReplayCompleted(own, "reconciliation-svc", listOf(first, second))
            verify(exactly = 1) { spied.onSnapshotComplete(any()) }
            context.environment
                .propertySources
                .addFirst(MapPropertySource("empty", mapOf(topicsSetting to "")))
            listener.onReplayCompleted(own, "reconciliation-svc", listOf(first, second))
            verify(exactly = 1) { spied.onSnapshotComplete(any()) }
        }
    }

    @Test
    fun `after a disconnect the replica is unsynced until a second own replay completes`() {
        val fresh = StructureReplica(CLOCK)
        on(runner(replica = fresh)).run { context ->
            val listener = context.getBean(ReplayCompletionListener::class.java)
            listener.onReplayCompleted(own, "reconciliation-svc", listOf(first, second))
            fresh.isSynced() shouldBe true
            fresh.onDisconnected()
            fresh.isSynced() shouldBe false
            listener.onReplayCompleted(own, "reconciliation-svc", listOf(first, second))
            fresh.isSynced() shouldBe true
        }
    }

    @Test
    fun `a context with both wirings starts and each intake receives its own listener`() {
        on(runner(catalogue = catalogue(withAuthority = true)))
            .withBean("authoritySubscription", ConsumerSubscription::class.java, {
                ConsumerSubscription(
                    "reconciliation-svc",
                    PlatformEventCatalogue.AUTHORITY_CHANGED.topic!!,
                    "reconciliation",
                )
            })
            .withBean(AuthorityReplica::class.java, { AuthorityReplica(CLOCK) })
            .withBean(com.dynokore.platform.productruntime.feed.AuthorityFeedHandler::class.java, {
                com.dynokore.platform.productruntime.feed
                    .AuthorityFeedHandler(AuthorityReplica(CLOCK))
            })
            .withConfiguration(AutoConfigurations.of(AuthorityReplicaWiring::class.java))
            .run { context ->
                context.startupFailure shouldBe null

                fun listenerOf(intake: String): Any? {
                    val bean = context.getBean(intake, JdbcReplayIntake::class.java)
                    return JdbcReplayIntake::class.java
                        .getDeclaredField(
                            "listener",
                        ).also { it.isAccessible = true }
                        .get(bean)
                }
                listenerOf("structureReplayIntake") shouldBe context.getBean("structureReplayCompletionListener")
                listenerOf("authorityReplayIntake") shouldBe context.getBean("authorityReplayCompletionListener")
            }
    }

    @Test
    fun `the wiring runs after the L1 composition`() {
        val after =
            StructureReplicaWiring::class.java
                .getAnnotation(AutoConfiguration::class.java)
                .afterName
                .single()
        Class.forName(after).simpleName shouldBe "L1CompositionAutoConfiguration"
    }
}
