package com.dynokore.gst.canonical.bootstrap

import com.dynokore.gst.canonical.adapters.events.EntityIdentityReader
import com.dynokore.gst.canonical.adapters.persistence.EntityIdentity
import com.dynokore.gst.canonical.adapters.persistence.JooqEntityIdentities
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
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * INV-REQ-479, ruling INV-Q-21451 A: the subscription of canonical-svc to `EntityProfileChanged` for the entity
 * identity table, as one inbox consumer with its own consumer name. The handler runs in the consumer's tenant
 * transaction and stores the `entityId` by `node.structureVersion` (version-guarded, idempotent). A malformed event
 * is a contract violation (dead letter).
 */
@Configuration(proxyBeanMethods = false)
class EntityIdentityFeed {
    @Bean
    fun entityIdentityHandler(dsl: DSLContext): EventHandler {
        val store = JooqEntityIdentities(dsl)
        return EntityIdentityHandler(EntityIdentityReader(), store::apply)
    }

    @Bean
    @Suppress("LongParameterList")
    fun entityIdentityConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        dsl: DSLContext,
        entityIdentityHandler: EventHandler,
    ): JdbcInboxConsumer =
        JdbcInboxConsumer(
            ConsumerSubscription(CONSUMER, TOPIC, SCHEMA),
            catalogue,
            reader,
            scope,
            dsl,
            entityIdentityHandler,
            rebuildRequired,
            clock,
        )

    companion object {
        const val CONSUMER = "canonical-svc-entity-identity"
        const val TOPIC = "gst.tenancy.entity-profile-changed.v1"
        const val EVENT_TYPE = "com.dynokore.gst.tenancy.EntityProfileChanged.v1"
        const val SCHEMA = "canonical"
    }
}

/** An event without an entity id, and one the store changed nothing for (a version not above), is IGNORED. */
class EntityIdentityHandler(
    private val reader: EntityIdentityReader,
    private val apply: (EntityIdentity) -> Boolean,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        val identity = reader.read(event.dataJson, event.tenant) ?: return HandleOutcome.IGNORED
        return if (apply(identity)) HandleOutcome.APPLIED else HandleOutcome.IGNORED
    }
}
