package com.dynokore.gst.canonical.bootstrap

import com.dynokore.gst.canonical.adapters.events.EntityConstitutionReader
import com.dynokore.gst.canonical.adapters.persistence.JooqEntityConstitutions
import com.dynokore.gst.canonical.application.entity.EntityConstitutionStore
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import org.jooq.DSLContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * INV-REQ-479, ruling INV-Q-21383 A: the subscription of canonical-svc to `EntityProfileChanged` as one inbox
 * consumer. The handler runs in the consumer's tenant transaction and upserts the entity constitution by
 * `node.structureVersion` (version-guarded, idempotent). A malformed event is a contract violation (dead letter).
 */
@Configuration(proxyBeanMethods = false)
class EntityConstitutionFeed {
    @Bean
    @ConditionalOnMissingBean(EntityConstitutionStore::class)
    fun entityConstitutionStore(dsl: DSLContext): EntityConstitutionStore = JooqEntityConstitutions(dsl)

    @Bean
    fun entityConstitutionHandler(store: EntityConstitutionStore): EventHandler =
        EntityConstitutionHandler(EntityConstitutionReader(), store)

    @Bean
    @Suppress("LongParameterList")
    fun entityConstitutionConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        dsl: DSLContext,
        entityConstitutionHandler: EventHandler,
    ): JdbcInboxConsumer =
        JdbcInboxConsumer(
            ConsumerSubscription(CONSUMER, TOPIC, SCHEMA),
            catalogue,
            reader,
            scope,
            dsl,
            entityConstitutionHandler,
            rebuildRequired,
            clock,
        )

    companion object {
        const val CONSUMER = "canonical-svc-entity-constitution"
        const val TOPIC = "gst.tenancy.entity-profile-changed.v1"
        const val EVENT_TYPE = "com.dynokore.gst.tenancy.EntityProfileChanged.v1"
        const val SCHEMA = "canonical"
    }
}

/** Reads the fact and applies it with at most one store call; an event without a constitution is IGNORED. */
class EntityConstitutionHandler(
    private val reader: EntityConstitutionReader,
    private val store: EntityConstitutionStore,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        val fact = reader.read(event.dataJson, event.tenant) ?: return HandleOutcome.IGNORED
        store.apply(fact)
        return HandleOutcome.APPLIED
    }
}
