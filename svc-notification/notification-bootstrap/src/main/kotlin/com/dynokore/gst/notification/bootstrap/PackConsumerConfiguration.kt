package com.dynokore.gst.notification.bootstrap

import com.dynokore.gst.notification.adapters.events.PackCurrencyChangedHandler
import com.dynokore.gst.notification.application.alerting.HandlePackCurrencyChanged
import com.dynokore.gst.notification.application.alerting.PackAlertStore
import com.dynokore.gst.notification.application.alerting.PackPolicySource
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper
import java.time.Clock

/**
 * The subscription of notification-svc to `PackCurrencyChanged` (INV-REQ-1080, INV-Q-20864 A), as one inbox consumer of
 * its own. The handler runs in the consumer's transaction, so the alert, the inbox row and the offset commit together.
 * The [RebuildRequiredSink] fallback is declared once by the backlog consumer configuration.
 */
@Configuration(proxyBeanMethods = false)
class PackConsumerConfiguration {
    @Bean
    fun handlePackCurrencyChanged(
        policy: PackPolicySource,
        store: PackAlertStore,
    ): HandlePackCurrencyChanged = HandlePackCurrencyChanged(policy, store)

    @Bean
    fun packCurrencyChangedHandler(
        useCase: HandlePackCurrencyChanged,
        mapper: ObjectMapper,
    ): PackCurrencyChangedHandler = PackCurrencyChangedHandler(useCase, mapper)

    @Bean
    @Suppress("LongParameterList")
    fun packConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        dsl: DSLContext,
        handler: PackCurrencyChangedHandler,
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

    companion object {
        const val CONSUMER = "notification-svc"
        const val TOPIC = PackCurrencyChangedHandler.TOPIC
        const val SCHEMA = "notification"
    }
}
