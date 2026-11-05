package com.dynokore.platform.productruntime

import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcReplayIntake
import com.dynokore.platform.outboxandqueue.OutboxSchema
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.ReplayCompletionListener
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.productruntime.feed.AuthorityFeedHandler
import com.dynokore.platform.productruntime.feed.AuthorityHeadFeed
import com.dynokore.platform.serviceruntime.rebuild.ReplicaColdStartSync
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.tenancyjooq.TenancyJooq
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * The authority replica's live inputs in a service that subscribes to the authority topic (INV-T-9059, owner ruling
 * INV-Q-9047 A, coordinator rulings INV-Q-13597 A, INV-Q-13599 A; INV-REQ-1201): the heartbeat feed, the intake of the
 * replay topic of the pair (access-svc, this consumer) and the listener that turns the service's own completed full
 * sync into the replica's snapshot. The sync itself is service-runtime's `ReplicaColdStartSync` (INV-T-13608).
 *
 * It runs after service-runtime's L1 composition, whose beans the conditions look for. A context with no
 * [ConsumerSubscription] to the authority topic, access-svc's own, gets none of these beans and the tracker is never
 * told to expect heartbeats.
 */
@AutoConfiguration(afterName = ["com.dynokore.platform.serviceruntime.composition.L1CompositionAutoConfiguration"])
class AuthorityReplicaWiring {
    @Bean
    @Conditional(OnAuthoritySubscription::class)
    @ConditionalOnBean(AuthorityReplica::class)
    fun authorityHeadFeed(replica: AuthorityReplica): AuthorityHeadFeed {
        replica.tracker.expectHeartbeats(HEARTBEAT_SILENCE)
        return AuthorityHeadFeed(replica)
    }

    @Bean
    @Conditional(OnAuthoritySubscription::class)
    @ConditionalOnBean(AuthorityReplica::class, ReplicaColdStartSync::class)
    fun authorityReplayCompletionListener(
        replica: AuthorityReplica,
        sync: ReplicaColdStartSync,
    ): ReplayCompletionListener =
        ReplayCompletionListener { replayId: UUID, _: String, topics: List<String> ->
            if (sync.isOwnReplay(replayId) && OnAuthoritySubscription.AUTHORITY_TOPIC in topics) {
                replica.onSnapshotComplete(replica.tracker.appliedVersion())
            }
        }

    @Bean
    @Conditional(OnAuthoritySubscription::class)
    @ConditionalOnBean(
        value = [
            RebuildRequiredSink::class, EventLogReader::class, TenantScope::class, EventCatalogue::class,
            ReplicaColdStartSync::class, AuthorityFeedHandler::class,
        ],
    )
    @Suppress("LongParameterList") // constructor injection under CS-05
    fun authorityReplayIntake(
        subscriptions: List<ConsumerSubscription>,
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        sink: RebuildRequiredSink,
        handler: AuthorityFeedHandler,
        authorityReplayCompletionListener: ReplayCompletionListener,
        clock: Clock,
    ): JdbcReplayIntake {
        val live = subscriptions.first { it.topic == OnAuthoritySubscription.AUTHORITY_TOPIC }
        val replay =
            checkNotNull(catalogue.replayTopic(ReplicaFeeds.ACCESS_SVC, live.consumer)) {
                "the event catalogue has no replay topic for the pair (producer ${ReplicaFeeds.ACCESS_SVC}, " +
                    "consumer ${live.consumer})"
            }
        return JdbcReplayIntake(
            ConsumerSubscription(live.consumer, replay.topic, live.schema),
            catalogue,
            ReplicaFeeds.ACCESS_SVC,
            reader,
            scope,
            TenancyJooq.dslContext(OutboxSchema.jooqTables(live.schema), scope.tenants, scope.transactions),
            handler,
            sink,
            clock,
            authorityReplayCompletionListener,
        )
    }

    private companion object {
        val HEARTBEAT_SILENCE: Duration = Duration.ofMillis(1000)
    }
}
