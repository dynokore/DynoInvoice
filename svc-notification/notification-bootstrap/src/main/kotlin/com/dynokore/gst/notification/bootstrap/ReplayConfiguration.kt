package com.dynokore.gst.notification.bootstrap

import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.JdbcWorkQueue
import com.dynokore.platform.outboxandqueue.LaneRateLimiter
import com.dynokore.platform.outboxandqueue.ReplayDelivery
import com.dynokore.platform.outboxandqueue.TenantScope
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * The one [ReplayDelivery] of notification-svc. The library bean injects exactly one `JdbcInboxConsumer`; this service
 * has two, so this bean makes it back off (precedent: svc-submission `submissionReplayDelivery`) and routes each item
 * by its topic to the consumer that owns it. Its queue claims only the items queued for `notification-svc`.
 */
@Configuration(proxyBeanMethods = false)
class ReplayConfiguration {
    @Bean
    @Suppress("LongParameterList")
    fun notificationReplayDelivery(
        scope: TenantScope,
        dsl: DSLContext,
        limiter: LaneRateLimiter,
        clock: Clock,
        mapper: tools.jackson.databind.ObjectMapper,
        @Qualifier("packConsumer") pack: JdbcInboxConsumer,
        @Qualifier("backlogConsumer") backlog: JdbcInboxConsumer,
    ): ReplayDelivery =
        TopicRoutingReplayDelivery(
            JdbcWorkQueue(
                scope,
                dsl,
                PackConsumerConfiguration.SCHEMA,
                limiter,
                clock,
                PackConsumerConfiguration.CONSUMER,
            ),
            scope,
            mapOf(
                PackConsumerConfiguration.TOPIC to pack,
                BacklogConsumerConfiguration.TOPIC to backlog,
            ),
            mapper,
        )
}
