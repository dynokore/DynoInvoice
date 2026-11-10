package com.dynokore.gst.submission.bootstrap

import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.OutboxSchema
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.productruntime.PlatformEventCatalogue
import com.dynokore.platform.tenancyjooq.TenancyJooq
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * The subscription to `BreakGlassSessionChanged` (INV-Q-9119 A, INV-REQ-896). product-runtime supplies the feed handler
 * bean, which writes `AuthorityReplica.breakGlassSessions`, but leaves the subscription to the service: the service
 * declares its own consumer of the topic, the way [ConsumerConfiguration] does for `PayloadReady`.
 */
@Configuration(proxyBeanMethods = false)
class BreakGlassFeed {
    @Bean
    @Suppress("LongParameterList")
    fun breakGlassSessionConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        @Qualifier("breakGlassSessionFeedHandler") handler: EventHandler,
    ): JdbcInboxConsumer {
        val subscription = ConsumerSubscription(CONSUMER, TOPIC, SCHEMA)
        return JdbcInboxConsumer(
            subscription,
            catalogue,
            reader,
            scope,
            TenancyJooq.dslContext(OutboxSchema.jooqTables(SCHEMA), scope.tenants, scope.transactions),
            handler,
            rebuildRequired,
            clock,
        )
    }

    companion object {
        const val CONSUMER = "submission-svc"
        const val SCHEMA = "submission"
        val TOPIC: String = checkNotNull(PlatformEventCatalogue.BREAK_GLASS_SESSION_CHANGED.topic)
    }
}
