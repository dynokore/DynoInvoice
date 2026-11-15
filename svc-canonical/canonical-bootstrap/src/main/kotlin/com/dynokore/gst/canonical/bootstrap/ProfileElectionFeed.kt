package com.dynokore.gst.canonical.bootstrap

import com.dynokore.gst.canonical.adapters.events.ProfileElectionReader
import com.dynokore.gst.canonical.adapters.persistence.JooqProfileElections
import com.dynokore.gst.canonical.adapters.persistence.ProfileElectionSet
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
 * INV-REQ-479, ruling INV-Q-21422 A: the subscription of canonical-svc to `EntityProfileChanged` for the profile
 * election table, as one inbox consumer with its own consumer name. The handler runs in the consumer's tenant
 * transaction and applies the purchaseDateBasis and gstScheme entries by `node.structureVersion` (version-guarded,
 * idempotent). A malformed event is a contract violation (dead letter).
 */
@Configuration(proxyBeanMethods = false)
class ProfileElectionFeed {
    @Bean
    fun profileElectionHandler(dsl: DSLContext): EventHandler {
        val store = JooqProfileElections(dsl)
        return ProfileElectionHandler(ProfileElectionReader(), store::apply)
    }

    @Bean
    @Suppress("LongParameterList")
    fun profileElectionConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        dsl: DSLContext,
        profileElectionHandler: EventHandler,
    ): JdbcInboxConsumer =
        JdbcInboxConsumer(
            ConsumerSubscription(CONSUMER, TOPIC, SCHEMA),
            catalogue,
            reader,
            scope,
            dsl,
            profileElectionHandler,
            rebuildRequired,
            clock,
        )

    companion object {
        const val CONSUMER = "canonical-svc-profile-election"
        const val TOPIC = "gst.tenancy.entity-profile-changed.v1"
        const val EVENT_TYPE = "com.dynokore.gst.tenancy.EntityProfileChanged.v1"
        const val SCHEMA = "canonical"
    }
}

/**
 * Reads the set and applies it with at most one store call. An event without an election entry, and a set the store
 * changed nothing for (a version not above the stored one), is IGNORED.
 */
class ProfileElectionHandler(
    private val reader: ProfileElectionReader,
    private val apply: (ProfileElectionSet) -> Boolean,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        val set = reader.read(event.dataJson, event.tenant) ?: return HandleOutcome.IGNORED
        return if (apply(set)) HandleOutcome.APPLIED else HandleOutcome.IGNORED
    }
}
