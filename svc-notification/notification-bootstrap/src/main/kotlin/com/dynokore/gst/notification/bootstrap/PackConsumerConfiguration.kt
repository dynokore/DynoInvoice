package com.dynokore.gst.notification.bootstrap

import com.dynokore.gst.notification.adapters.events.PackCurrencyChangedHandler
import com.dynokore.gst.notification.adapters.tenants.StructureRegisteredTenants
import com.dynokore.gst.notification.adapters.tenants.TenantScopeTransactions
import com.dynokore.gst.notification.application.alerting.FanOutPackCurrencyChanged
import com.dynokore.gst.notification.application.alerting.HandlePackCurrencyChanged
import com.dynokore.gst.notification.application.alerting.PackAlertStore
import com.dynokore.gst.notification.application.alerting.PackPolicySource
import com.dynokore.gst.notification.application.alerting.PerTenantTransaction
import com.dynokore.gst.notification.application.alerting.RegisteredTenantSource
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper
import java.time.Clock

/**
 * The subscription of notification-svc to the platform-class `PackCurrencyChanged` (INV-REQ-1080, INV-Q-20864 A), as
 * one inbox consumer of its own. The handler runs in the consumer's platform transaction (inbox row, offset); each
 * tenant's alert commits in a transaction of its own under that tenant.
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
    fun registeredTenantSource(structure: StructureReplica): RegisteredTenantSource =
        StructureRegisteredTenants(structure)

    @Bean
    fun perTenantTransaction(scope: TenantScope): PerTenantTransaction = TenantScopeTransactions(scope)

    @Bean
    fun fanOutPackCurrencyChanged(
        tenants: RegisteredTenantSource,
        transactions: PerTenantTransaction,
        handler: HandlePackCurrencyChanged,
    ): FanOutPackCurrencyChanged = FanOutPackCurrencyChanged(tenants, transactions, handler)

    @Bean
    fun packCurrencyChangedHandler(
        useCase: FanOutPackCurrencyChanged,
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
        @Qualifier("packCurrencyChangedHandler") handler: EventHandler,
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
