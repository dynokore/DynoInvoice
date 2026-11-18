package com.dynokore.gst.reconciliation.bootstrap

import com.dynokore.gst.reconciliation.adapters.policy.PolicyReplicaView
import com.dynokore.gst.reconciliation.application.workpaper.export.ListRelaxedControls
import com.dynokore.gst.reconciliation.application.workpaper.export.RelaxedControlsSource
import com.dynokore.platform.configresolver.VariationPointCatalogue
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.InboxConsumer
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.productruntime.feed.PolicyFeed
import com.dynokore.platform.serviceruntime.replica.PolicyReplica
import org.jooq.DSLContext
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.time.Clock
import java.time.Duration

/** The policy replica of reconciliation-svc: the `PolicyChanged` subscription that fills it (INV-REQ-982 item 3). */
@Configuration(proxyBeanMethods = false)
class PolicyReplicaConfiguration {
    @Bean
    @Suppress("LongParameterList")
    fun policyChangedConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        dsl: DSLContext,
        feed: PolicyFeed,
    ): InboxConsumer {
        checkNotNull(
            catalogue.topic(POLICY_CHANGED.topic),
        ) { "the event catalogue has no entry on ${POLICY_CHANGED.topic}" }
        return JdbcInboxConsumer(
            POLICY_CHANGED,
            catalogue,
            reader,
            scope,
            dsl,
            PolicyChangedHandler(feed),
            rebuildRequired,
            clock,
        )
    }

    /** The lag bound is `reconciliation.policy-replica.max-lag` (ISO-8601, no default in code). */
    @Bean
    fun policyReplicaView(
        replica: PolicyReplica,
        catalogue: VariationPointCatalogue,
        environment: Environment,
    ): PolicyReplicaView =
        PolicyReplicaView(
            replica,
            catalogue,
            Binder
                .get(environment)
                .bind(MAX_LAG_PROPERTY, Duration::class.java)
                .orElseThrow {
                    IllegalStateException(
                        "the property $MAX_LAG_PROPERTY (ISO-8601 duration) is required",
                    )
                },
        )

    @Bean
    fun relaxedControlsSource(view: PolicyReplicaView): RelaxedControlsSource = ListRelaxedControls(view)

    companion object {
        const val MAX_LAG_PROPERTY = "reconciliation.policy-replica.max-lag"
        const val POLICY_CHANGED_TOPIC = "gst.platform.policy-changed.v1"
        val POLICY_CHANGED =
            ConsumerSubscription(
                "reconciliation-svc-policy-changed",
                POLICY_CHANGED_TOPIC,
                RunReadModelConsumerConfiguration.SCHEMA,
            )
    }
}
