package com.dynokore.gst.notification.bootstrap

import com.dynokore.gst.notification.adapters.events.BacklogValuesMeasuredHandler
import com.dynokore.gst.notification.application.alerting.BacklogAlertStore
import com.dynokore.gst.notification.application.alerting.BacklogThresholdSource
import com.dynokore.gst.notification.application.alerting.HandleBacklogValues
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper
import java.time.Clock

/**
 * The subscription of notification-svc to `BacklogValuesMeasured` (INV-REQ-1370), as one inbox consumer of its own
 * (product-runtime also supplies `EventHandler` beans, so the default single-consumer wiring backs off). The handler
 * runs in the consumer's transaction, so the alert, the inbox row and the offset commit together (INV-REQ-1222).
 */
@Configuration(proxyBeanMethods = false)
class BacklogConsumerConfiguration {
    @Bean
    fun handleBacklogValues(
        thresholds: BacklogThresholdSource,
        store: BacklogAlertStore,
        clock: Clock,
    ): HandleBacklogValues = HandleBacklogValues(thresholds, store, clock)

    @Bean
    fun backlogValuesMeasuredHandler(
        useCase: HandleBacklogValues,
        mapper: ObjectMapper,
    ): BacklogValuesMeasuredHandler = BacklogValuesMeasuredHandler(useCase, mapper)

    @Bean
    @Suppress("LongParameterList")
    fun backlogConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        dsl: DSLContext,
        handler: BacklogValuesMeasuredHandler,
    ): JdbcInboxConsumer =
        JdbcInboxConsumer(
            ConsumerSubscription(CONSUMER, TOPIC, SCHEMA),
            catalogue,
            reader,
            scope,
            dsl,
            handler,
            rebuildRequired,
            clock,
        )

    @Bean
    @ConditionalOnMissingBean(RebuildRequiredSink::class)
    fun loggingRebuildRequiredSink(): RebuildRequiredSink =
        RebuildRequiredSink {
            LoggerFactory
                .getLogger(BacklogConsumerConfiguration::class.java)
                .error(
                    "consumer {} must be rebuilt: topic {} next offset {} is at or below the watermark {}",
                    it.consumer,
                    it.topic,
                    it.nextOffset,
                    it.watermark,
                )
        }

    companion object {
        const val CONSUMER = "notification-svc"
        const val TOPIC = BacklogValuesMeasuredHandler.TOPIC
        const val SCHEMA = "notification"
    }
}
