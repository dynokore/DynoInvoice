package com.dynokore.platform.productruntime

import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.InboxConsumer
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.JdbcReplayIntake
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.ReplayCompletionListener
import com.dynokore.platform.outboxandqueue.ReplayProtocol
import com.dynokore.platform.outboxandqueue.ReplayRequestTopic
import com.dynokore.platform.outboxandqueue.ReplayTopic
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.productruntime.Fixtures.CLOCK
import com.dynokore.platform.productruntime.feed.AuthorityHeadFeed
import com.dynokore.platform.serviceruntime.binding.GstProperties
import com.dynokore.platform.serviceruntime.rebuild.ReplicaColdStartSync
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.Lag
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.PolicyReplica
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.CurrentConnection
import com.dynokore.platform.tenancykernel.SqlErrors
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * INV-T-9059 (P109, TPL-18): the heartbeat feed, the replay intake and the completion listener exist exactly in a
 * context with a subscription to the authority topic (compared by presence), and the listener turns only the
 * service's own completed replay of that topic into the replica's snapshot.
 */
class AuthorityReplicaWiringTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(GstProperties::class)
    class Properties

    private val authority = PlatformEventCatalogue.AUTHORITY_CHANGED.topic!!
    private val own = UUID.randomUUID()
    private val sync =
        mockk<ReplicaColdStartSync>(relaxed = true) {
            every { isOwnReplay(any()) } answers
                { firstArg<UUID>() == own }
        }
    private val runnerStub =
        mockk<TransactionRunner>(moreInterfaces = arrayOf(SqlErrors::class, CurrentConnection::class))
    private val scope = TenantScope(mockk<TenantContext>(), runnerStub, runnerStub as CurrentConnection, "svc-workload")

    private fun catalogue(consumer: String = "reconciliation-svc") =
        EventCatalogue(
            listOf(CatalogueEntry(PlatformEventCatalogue.AUTHORITY_CHANGED.eventType, authority, setOf("access-svc"))),
            listOf(
                ReplayTopic(
                    "gst.access.replay-reconciliation.v1",
                    setOf(PlatformEventCatalogue.AUTHORITY_CHANGED.eventType, ReplayProtocol.REPLAY_COMPLETED),
                    "access-svc",
                    consumer,
                    false,
                ),
            ),
            listOf(ReplayRequestTopic("gst.reconciliation.replay-requested.v1", consumer, setOf("access-svc"))),
        )

    private lateinit var replica: AuthorityReplica

    @BeforeEach
    fun freshReplica() {
        replica = AuthorityReplica(CLOCK, OperationRoleMap { emptyMap() })
    }

    private fun runner(
        topic: String?,
        catalogue: EventCatalogue = catalogue(),
        authorityReplica: AuthorityReplica = replica,
    ): ApplicationContextRunner {
        val base =
            ApplicationContextRunner()
                .withUserConfiguration(Properties::class.java)
                .withBean(Clock::class.java, { CLOCK })
                .withBean(EventCatalogue::class.java, { catalogue })
                .withBean(TenantScope::class.java, { scope })
                .withBean(EventLogReader::class.java, { mockk<EventLogReader>() })
                .withBean(RebuildRequiredSink::class.java, { mockk<RebuildRequiredSink>() })
                .withBean(ReplicaColdStartSync::class.java, { sync })
                .withBean(AuthorityReplica::class.java, { authorityReplica })
                .withBean(StructureReplica::class.java, { StructureReplica(CLOCK) })
                .withBean(PolicyReplica::class.java, {
                    PolicyReplica(CLOCK)
                })
                .withBean("internalRestClientBuilder", RestClient.Builder::class.java, { RestClient.builder() })
                .withConfiguration(
                    AutoConfigurations.of(ProductRuntimeConfiguration::class.java, AuthorityReplicaWiring::class.java),
                ).withPropertyValues("gst.service=reconciliation-svc")
        return if (topic == null) {
            base
        } else {
            base.withBean("subscription", ConsumerSubscription::class.java, {
                ConsumerSubscription("reconciliation-svc", topic, "reconciliation")
            })
        }
    }

    private val installed =
        listOf(AuthorityHeadFeed::class.java, JdbcReplayIntake::class.java, ReplayCompletionListener::class.java)

    @Test
    fun `a subscription to the authority topic installs the feed, intake and listener`() {
        runner(authority).run { context ->
            context.startupFailure shouldBe null
            installed.forEach { context.getBeansOfType(it).size shouldBe 1 }
            context.getBean("authorityReplayIntake").shouldBeInstanceOf<JdbcReplayIntake>()
            listOf("authorityHeadFeed", "authorityReplayIntake", "authorityReplayCompletionListener").forEach {
                context.beanFactory.getBeanDefinition(it).factoryBeanName shouldBe
                    AuthorityReplicaWiring::class.java.name
            }
            // expectHeartbeats(1000 ms) was called by the feed bean: a second call is refused
            assertThrows<IllegalStateException> { replica.tracker.expectHeartbeats(Duration.ofMillis(1000)) }
        }
    }

    @Test
    fun `no subscription, a subscription to another topic or to the retired access topic installs none of them`() {
        listOf(
            null,
            "gst.tenancy.organisation-structure-changed.v1",
            "gst.access.authority-changed.v1",
            "gst.platform.authority-changed.v2",
        ).forEach { topic ->
            runner(topic, authorityReplica = AuthorityReplica(CLOCK, OperationRoleMap { emptyMap() })).run { context ->
                context.startupFailure shouldBe null
                installed.forEach { context.getBeansOfType(it).size shouldBe 0 }
                // expectHeartbeats is never called: the one allowed call is still open
                context.getBean(AuthorityReplica::class.java).tracker.expectHeartbeats(Duration.ofMillis(1000))
            }
        }
    }

    @Test
    fun `the listener sets the snapshot only for an own replay whose topics hold the authority topic`() {
        val other = "gst.tenancy.organisation-structure-changed.v1"
        val retired = "gst.access.authority-changed.v1"
        val cases =
            listOf(
                Triple(own, listOf(authority), true),
                Triple(own, listOf(other), false),
                Triple(own, listOf(retired), false),
                Triple(own, emptyList(), false),
                Triple(UUID.randomUUID(), listOf(authority), false),
            )
        cases.forEach { (replayId, topics, snapshot) ->
            val fresh = AuthorityReplica(CLOCK, OperationRoleMap { emptyMap() })
            runner(authority, authorityReplica = fresh).run { context ->
                fresh.tracker.onApplied(7)
                val listener = context.getBean(ReplayCompletionListener::class.java)
                listener.onReplayCompleted(replayId, "reconciliation-svc", topics)
                (fresh.tracker.lag() is Lag.Measured) shouldBe snapshot
                fresh.tracker.appliedVersion() shouldBe 7
            }
        }
    }

    @Test
    fun `a catalogue without the replay topic of the pair stops the start and names the pair`() {
        runner(authority, catalogue(consumer = "other-svc")).run { context ->
            context.startupFailure.toString() shouldContain "producer access-svc"
            context.startupFailure.toString() shouldContain "consumer reconciliation-svc"
        }
    }

    @Test
    fun `the wiring runs after the L1 composition, whose beans its conditions look for`() {
        val after =
            AuthorityReplicaWiring::class.java
                .getAnnotation(AutoConfiguration::class.java)
                .afterName
                .single()
        Class.forName(after).simpleName shouldBe "L1CompositionAutoConfiguration"
    }

    /** A runner built with service-runtime's auto-configurations (INV-Q-16934 A) and one primary service handler. */
    private fun l1Runner(): ApplicationContextRunner =
        runner(authority)
            .withConfiguration(
                AutoConfigurations.of(
                    Class.forName("com.dynokore.platform.serviceruntime.composition.L1CompositionAutoConfiguration"),
                ),
            ).withBean("serviceHandler", EventHandler::class.java, { mockk<EventHandler>() }, { it.isPrimary = true })

    @Test
    fun `the default live consumer is kept beside the replay intake`() {
        l1Runner().run { context ->
            context.startupFailure shouldBe null
            context.getBeansOfType(JdbcInboxConsumer::class.java).keys shouldBe setOf("inboxConsumer")
            context.getBeansOfType(JdbcReplayIntake::class.java).keys shouldBe setOf("authorityReplayIntake")
        }
    }

    @Test
    fun `a service-declared inbox consumer of another class replaces the default and the intake stays`() {
        l1Runner()
            .withBean("ownConsumer", InboxConsumer::class.java, { mockk<InboxConsumer>(relaxed = true) })
            .run { context ->
                context.startupFailure shouldBe null
                context.getBeansOfType(JdbcInboxConsumer::class.java).keys shouldBe emptySet()
                context.getBeansOfType(JdbcReplayIntake::class.java).keys shouldBe setOf("authorityReplayIntake")
            }
    }

    @Test
    fun `without an authority subscription neither the consumer nor the intake exists`() {
        runner(null)
            .withConfiguration(
                AutoConfigurations.of(
                    Class.forName("com.dynokore.platform.serviceruntime.composition.L1CompositionAutoConfiguration"),
                ),
            ).withBean("serviceHandler", EventHandler::class.java, { mockk<EventHandler>() }, { it.isPrimary = true })
            .run { context ->
                context.startupFailure shouldBe null
                context.getBeansOfType(JdbcInboxConsumer::class.java).keys shouldBe emptySet()
                context.getBeansOfType(JdbcReplayIntake::class.java).keys shouldBe emptySet()
            }
    }
}
