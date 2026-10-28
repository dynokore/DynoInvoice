package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.adapters.persistence.ChainWiring
import com.dynokore.gst.evidence.adapters.persistence.ObjectStoreDigestObjects
import com.dynokore.gst.evidence.application.digest.DigestObjects
import com.dynokore.gst.evidence.application.digest.MaxRetention
import com.dynokore.platform.evidencestore.ObjectKey
import com.dynokore.platform.evidencestore.ObjectRef
import com.dynokore.platform.evidencestore.ObjectStore
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import org.jooq.DSLContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Instant

/**
 * The subscription of evidence-svc to `GstReturnRecorded` (INV-Q-7251 A (1)): the filing of a return writes the period
 * digest. Same inbox, offset and dead-letter tables as the record consumers, and the handler runs in the consumer's
 * transaction.
 *
 * The object store and the longest retention have no production source in this service yet. Until a bean supplies
 * them, the fallbacks below refuse every call, so the consumer fails closed and retries rather than writing a digest
 * without its write-once copy or with a guessed retention.
 */
@Configuration(proxyBeanMethods = false)
class ReturnConsumerConfiguration {
    @Bean
    @Suppress("LongParameterList")
    fun gstReturnRecordedConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        dsl: DSLContext,
        objects: DigestObjects,
        retention: MaxRetention,
    ): JdbcInboxConsumer =
        JdbcInboxConsumer(
            ConsumerSubscription(LocatorConsumerConfiguration.CONSUMER, GST_RETURN_RECORDED_TOPIC, ChainWiring.SCHEMA),
            catalogue,
            reader,
            scope,
            dsl,
            ChainWiring.gstReturnRecordedHandler(dsl, objects, retention, clock),
            rebuildRequired,
            clock,
        )

    @Bean
    @ConditionalOnMissingBean(DigestObjects::class)
    fun digestObjects(store: ObjectStore): DigestObjects = ObjectStoreDigestObjects(store)

    @Bean
    @ConditionalOnMissingBean(ObjectStore::class)
    fun unconfiguredObjectStore(): ObjectStore = UnconfiguredObjectStore

    @Bean
    @ConditionalOnMissingBean(MaxRetention::class)
    fun unconfiguredMaxRetention(): MaxRetention =
        MaxRetention {
            error("no source of the longest evidence retention is configured")
        }

    private object UnconfiguredObjectStore : ObjectStore {
        override fun put(
            key: ObjectKey,
            bytes: ByteArray,
            retainUntil: Instant,
        ): ObjectRef = error("no object store is configured")

        override fun get(key: ObjectKey): ByteArray = error("no object store is configured")
    }

    companion object {
        const val GST_RETURN_RECORDED_TOPIC = "gst.reconciliation.gst-return-recorded.v1"
    }
}
