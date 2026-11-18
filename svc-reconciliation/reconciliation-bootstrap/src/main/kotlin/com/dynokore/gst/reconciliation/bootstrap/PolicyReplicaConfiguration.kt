package com.dynokore.gst.reconciliation.bootstrap

import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.InboxConsumer
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.productruntime.feed.PolicyFeed
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

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

    companion object {
        const val POLICY_CHANGED_TOPIC = "gst.platform.policy-changed.v1"
        val POLICY_CHANGED =
            ConsumerSubscription(
                "reconciliation-svc-policy-changed",
                POLICY_CHANGED_TOPIC,
                RunReadModelConsumerConfiguration.SCHEMA,
            )
    }
}
