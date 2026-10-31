package com.dynokore.platform.serviceruntime.composition

import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventLogHead
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.binding.GstProperties
import com.dynokore.platform.serviceruntime.rebuild.ConsumerRebuildAuthorizer
import com.dynokore.platform.serviceruntime.rebuild.ConsumerRebuildRuntime
import com.dynokore.platform.serviceruntime.rebuild.ReplicaColdStartSync
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.time.Clock

/**
 * Wires the authority replica's start-up full sync (INV-T-13608; owner ruling INV-Q-9047 A, coordinator rulings
 * INV-Q-13599 A, INV-Q-13597 A; INV-REQ-1201): the [ConsumerRebuildRuntime] over the service's own beans and the
 * [ReplicaColdStartSync] over it and the [AuthorityReplica].
 *
 * Fail closed: the default [ConsumerRebuildAuthorizer] refuses every actor, so it adds no operator path. With no
 * [ConsumerSubscription] bean the schema is undefined, so neither the runtime nor the sync exists and no full sync is
 * requested. Subscriptions of different schemas stop the start.
 *
 * The structure replica's snapshot (INV-T-9094; ruling INV-Q-9081 A, INV-Q-13691 A): [REQUIRE_SNAPSHOT] (absent means
 * false) and [SNAPSHOT_TOPICS] (absent means empty) come from the `Environment`. With the setting true and no
 * [StructureReplica] bean, an empty list, or a listed topic that the [EventCatalogue] does not list with `tenancy-svc`
 * among its producers or that has no [ConsumerSubscription], the context fails to start with a message naming the
 * setting. With the setting false nothing is checked and no structure request is ever written.
 */
@Configuration(proxyBeanMethods = false)
class ReplicaColdStartWiring {
    @Bean
    @ConditionalOnMissingBean(ConsumerRebuildAuthorizer::class)
    fun consumerRebuildAuthorizer(): ConsumerRebuildAuthorizer = ConsumerRebuildAuthorizer { false }

    @Bean
    @ConditionalOnBean(
        value = [
            ConsumerSubscription::class, EventCatalogue::class, TenantScope::class, OutboxWriter::class,
            AuditWriter::class, EventLogHead::class,
        ],
    )
    @ConditionalOnMissingBean(ConsumerRebuildRuntime::class)
    @Suppress("LongParameterList") // constructor injection under CS-05
    fun consumerRebuildRuntime(
        gst: GstProperties,
        subscriptions: List<ConsumerSubscription>,
        catalogue: EventCatalogue,
        scope: TenantScope,
        outbox: OutboxWriter,
        audit: AuditWriter,
        head: EventLogHead,
        authorizer: ConsumerRebuildAuthorizer,
        clock: Clock,
    ): ConsumerRebuildRuntime {
        val schemas = subscriptions.map { it.schema }.distinct()
        check(schemas.size == 1) {
            "the ConsumerSubscription beans must all have the same schema, found: ${schemas.joinToString(", ")}"
        }
        return ConsumerRebuildRuntime(
            gst.requireService(),
            schemas.single(),
            catalogue,
            subscriptions,
            scope,
            outbox,
            audit,
            head,
            authorizer,
            clock,
            null,
        )
    }

    @Bean
    @ConditionalOnBean(ConsumerRebuildRuntime::class, AuthorityReplica::class)
    @ConditionalOnMissingBean(ReplicaColdStartSync::class)
    @Suppress("LongParameterList") // constructor injection under CS-05
    fun replicaColdStartSync(
        runtime: ConsumerRebuildRuntime,
        replica: AuthorityReplica,
        structure: ObjectProvider<StructureReplica>,
        catalogue: ObjectProvider<EventCatalogue>,
        subscriptions: ObjectProvider<ConsumerSubscription>,
        environment: Environment,
    ): ReplicaColdStartSync {
        val binder = Binder.get(environment)
        val require = binder.bind(REQUIRE_SNAPSHOT, Boolean::class.javaObjectType).orElse(false) ?: false
        if (!require) return ReplicaColdStartSync(runtime, replica)
        val topics =
            binder.bind(SNAPSHOT_TOPICS, Bindable.listOf(String::class.java)).orElse(emptyList()).orEmpty()
        val events = catalogue.ifAvailable
        val replicaBean =
            checkNotNull(structure.ifAvailable) {
                "$REQUIRE_SNAPSHOT is true but the context has no StructureReplica bean"
            }
        check(topics.isNotEmpty()) { "$REQUIRE_SNAPSHOT is true but $SNAPSHOT_TOPICS is empty" }
        val subscribed =
            subscriptions
                .orderedStream()
                .map { it.topic }
                .toList()
                .toSet()
        topics.forEach { topic ->
            val entry = events?.topic(topic)
            check(entry != null && ReplicaColdStartSync.TENANCY in entry.producers) {
                "$SNAPSHOT_TOPICS lists '$topic', which the event catalogue does not list with " +
                    "${ReplicaColdStartSync.TENANCY} as a producer"
            }
            check(topic in subscribed) { "$SNAPSHOT_TOPICS lists '$topic', which has no ConsumerSubscription" }
        }
        return ReplicaColdStartSync(
            runtime,
            replica,
            ReplicaColdStartSync.DEFAULT_RECHECK_INTERVAL,
            replicaBean,
            topics,
            true,
        )
    }

    private companion object {
        const val REQUIRE_SNAPSHOT = ReplicaColdStartSync.REQUIRE_SNAPSHOT_SETTING
        const val SNAPSHOT_TOPICS = "serviceruntime.replica.structure.snapshot-topics"
    }
}
