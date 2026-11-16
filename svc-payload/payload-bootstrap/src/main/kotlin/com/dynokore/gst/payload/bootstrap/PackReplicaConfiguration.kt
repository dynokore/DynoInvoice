package com.dynokore.gst.payload.bootstrap

import com.dynokore.gst.payload.adapters.packs.JooqMdePackReplica
import com.dynokore.gst.payload.adapters.packs.RulePackEventsHandler
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * The MDE pack replica of payload-svc (event-carried state transfer, INV-AC-23578): the store over
 * `payload.mde_pack_replica` and one inbox consumer per event, on the inbox, offset and dead-letter tables of schema
 * `payload` as [VersionFactsConsumerConfiguration]. Each consumer subscribes by the exact type and topic of its event
 * and refuses to be built when the catalogue does not list the type on exactly that topic (fail closed). The replay
 * delivery routes neither type: a replayed item of these types stays queued.
 */
@Configuration(proxyBeanMethods = false)
class PackReplicaConfiguration {
    @Bean
    fun mdePackReplica(
        dsl: DSLContext,
        tenants: TenantContext,
    ): JooqMdePackReplica = JooqMdePackReplica(dsl) { tenants.current().value }

    @Bean
    @Suppress("LongParameterList")
    fun rulePackStagedConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        dsl: DSLContext,
        replica: JooqMdePackReplica,
    ): JdbcInboxConsumer =
        consumer(
            RulePackEventsHandler.STAGED_TYPE,
            RulePackEventsHandler.STAGED_TOPIC,
            catalogue,
            reader,
            scope,
            rebuildRequired,
            clock,
            dsl,
            replica,
        )

    @Bean
    @Suppress("LongParameterList")
    fun rulePackActivatedConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        dsl: DSLContext,
        replica: JooqMdePackReplica,
    ): JdbcInboxConsumer =
        consumer(
            RulePackEventsHandler.ACTIVATED_TYPE,
            RulePackEventsHandler.ACTIVATED_TOPIC,
            catalogue,
            reader,
            scope,
            rebuildRequired,
            clock,
            dsl,
            replica,
        )

    @Suppress("LongParameterList")
    private fun consumer(
        type: String,
        topic: String,
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        dsl: DSLContext,
        replica: JooqMdePackReplica,
    ): JdbcInboxConsumer {
        val entry = catalogue.find(type)
        check(entry != null && entry.topic == topic) { "the event catalogue does not list $type on $topic" }
        return JdbcInboxConsumer(
            ConsumerSubscription(CONSUMER, topic, SCHEMA),
            catalogue,
            reader,
            scope,
            dsl,
            RulePackEventsHandler(type, replica),
            rebuildRequired,
            clock,
        )
    }

    companion object {
        const val CONSUMER = "payload-svc"
        const val SCHEMA = "payload"
    }
}
