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
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * Wires the authority replica's start-up full sync (INV-T-13608; owner ruling INV-Q-9047 A, coordinator rulings
 * INV-Q-13599 A, INV-Q-13597 A; INV-REQ-1201): the [ConsumerRebuildRuntime] over the service's own beans and the
 * [ReplicaColdStartSync] over it and the [AuthorityReplica].
 *
 * Fail closed: the default [ConsumerRebuildAuthorizer] refuses every actor, so it adds no operator path. With no
 * [ConsumerSubscription] bean the schema is undefined, so neither the runtime nor the sync exists and no full sync is
 * requested. Subscriptions of different schemas stop the start.
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
    fun replicaColdStartSync(
        runtime: ConsumerRebuildRuntime,
        replica: AuthorityReplica,
    ): ReplicaColdStartSync = ReplicaColdStartSync(runtime, replica)
}
