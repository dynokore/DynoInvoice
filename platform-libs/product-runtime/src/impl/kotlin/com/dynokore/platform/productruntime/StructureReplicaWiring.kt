package com.dynokore.platform.productruntime

import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcReplayIntake
import com.dynokore.platform.outboxandqueue.OutboxSchema
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.ReplayCompletionListener
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.productruntime.feed.StructureFeedHandler
import com.dynokore.platform.serviceruntime.rebuild.ReplicaColdStartSync
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancyjooq.TenancyJooq
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionOutcome
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.SpringBootCondition
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.core.env.Environment
import org.springframework.core.type.AnnotatedTypeMetadata
import java.time.Clock
import java.util.UUID

/**
 * The structure replica's snapshot from the service's own full sync (INV-T-9095; coordinator rulings INV-Q-17064 A,
 * INV-Q-17062 A, INV-Q-13691 A as amended by INV-Q-13786 A; INV-REQ-1199): the intake of the replay topic of the pair
 * (tenancy-svc, this consumer) and the listener that completes the structure replica's snapshot when the service's own
 * replay has moved every value of `serviceruntime.replica.structure.snapshot-topics` (compared as a set).
 *
 * Both beans exist only when [ReplicaColdStartSync.REQUIRE_SNAPSHOT_SETTING] is exactly `true` (any other value or
 * none: neither) and the context has the structure replica, the sync and the structure feed handler. The listener is
 * called only by the intake. This class adds no caller of `StructureReplica.onDisconnected()`.
 */
@AutoConfiguration(afterName = ["com.dynokore.platform.serviceruntime.composition.L1CompositionAutoConfiguration"])
class StructureReplicaWiring {
    @Bean
    @Conditional(OnRequireSnapshot::class)
    @ConditionalOnBean(StructureReplica::class, ReplicaColdStartSync::class, StructureFeedHandler::class)
    fun structureReplayCompletionListener(
        replica: StructureReplica,
        sync: ReplicaColdStartSync,
        environment: Environment,
    ): ReplayCompletionListener =
        ReplayCompletionListener { replayId: UUID, _: String, topics: List<String> ->
            val listed = snapshotTopics(environment)
            if (sync.isOwnReplay(replayId) && listed.isNotEmpty() && topics.containsAll(listed)) {
                replica.onSnapshotComplete(replica.replica.tracker.appliedVersion())
            }
        }

    @Bean
    @Conditional(OnRequireSnapshot::class)
    @ConditionalOnBean(
        value = [
            RebuildRequiredSink::class, EventLogReader::class, TenantScope::class, EventCatalogue::class,
            StructureReplica::class, ReplicaColdStartSync::class, StructureFeedHandler::class,
        ],
    )
    @Suppress("LongParameterList") // constructor injection under CS-05
    fun structureReplayIntake(
        subscriptions: List<ConsumerSubscription>,
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        sink: RebuildRequiredSink,
        handler: StructureFeedHandler,
        @Qualifier("structureReplayCompletionListener") listener: ReplayCompletionListener,
        environment: Environment,
        clock: Clock,
    ): JdbcReplayIntake {
        val listed = snapshotTopics(environment)
        val live = subscriptions.filter { it.topic in listed }
        check(listed.isNotEmpty() && live.map { it.topic }.toSet() == listed.toSet()) {
            "$SNAPSHOT_TOPICS must list topics that all have a ConsumerSubscription, found: $listed"
        }
        check(live.map { it.consumer }.distinct().size == 1 && live.map { it.schema }.distinct().size == 1) {
            "the subscriptions to the topics of $SNAPSHOT_TOPICS must all have the same consumer and schema"
        }
        val first = live.first { it.topic == listed.first() }
        val replay =
            checkNotNull(catalogue.replayTopic(ReplicaFeeds.TENANCY_SVC, first.consumer)) {
                "the event catalogue has no replay topic for the pair (producer ${ReplicaFeeds.TENANCY_SVC}, " +
                    "consumer ${first.consumer})"
            }
        return JdbcReplayIntake(
            ConsumerSubscription(first.consumer, replay.topic, first.schema),
            catalogue,
            ReplicaFeeds.TENANCY_SVC,
            reader,
            scope,
            TenancyJooq.dslContext(OutboxSchema.jooqTables(first.schema), scope.tenants, scope.transactions),
            handler,
            sink,
            clock,
            listener,
        )
    }

    /** Matches only when the setting is exactly the text `true`: `True`, `yes`, `1`, blank and absent do not. */
    class OnRequireSnapshot : SpringBootCondition() {
        override fun getMatchOutcome(
            context: ConditionContext,
            metadata: AnnotatedTypeMetadata,
        ): ConditionOutcome =
            if (context.environment.getProperty(ReplicaColdStartSync.REQUIRE_SNAPSHOT_SETTING) == "true") {
                ConditionOutcome.match("${ReplicaColdStartSync.REQUIRE_SNAPSHOT_SETTING} is true")
            } else {
                ConditionOutcome.noMatch("${ReplicaColdStartSync.REQUIRE_SNAPSHOT_SETTING} is not exactly true")
            }
    }

    private companion object {
        const val SNAPSHOT_TOPICS = "serviceruntime.replica.structure.snapshot-topics"

        fun snapshotTopics(environment: Environment): List<String> =
            Binder
                .get(environment)
                .bind(SNAPSHOT_TOPICS, Bindable.listOf(String::class.java))
                .orElse(emptyList())
                .orEmpty()
    }
}
